package com.eap.mcp.recovery;

import com.eap.common.recovery.RecoveryActionType;
import com.eap.common.recovery.BrokerReplayPreflightResult;
import com.eap.common.recovery.RecoveryCaseDetail;
import com.eap.common.recovery.RecoveryCaseId;
import com.eap.common.recovery.RecoveryCaseSummary;
import com.eap.common.recovery.RecoveryDryRunRequest;
import com.eap.common.recovery.RecoveryDryRunResult;
import com.eap.common.recovery.RecoveryExecuteRequest;
import com.eap.common.recovery.RecoveryExecuteResult;
import com.eap.common.recovery.RecoveryExecutionStatus;
import com.eap.mcp.recovery.api.ManagedRecoveryCase;
import com.eap.mcp.recovery.api.ManagedRecoveryCaseDetail;
import com.eap.mcp.recovery.api.RecoveryCaseListResponse;
import com.eap.mcp.recovery.api.RecoveryControlActionRequest;
import com.eap.mcp.recovery.api.RecoverySourceError;
import com.eap.mcp.recovery.client.MatchRecoverySourceClient;
import com.eap.mcp.recovery.client.OrderRecoverySourceClient;
import com.eap.mcp.recovery.client.RecoverySourceApi;
import com.eap.mcp.recovery.client.WalletRecoverySourceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class RecoveryControlService {

    private static final int MAX_LIMIT = 100;

    private final Map<String, RecoverySourceApi> sources;
    private final RecoveryAuditStore auditStore;
    private final BrokerDeadLetterStore brokerDeadLetters;
    private final BrokerDeadLetterReplayCoordinator brokerReplay;
    private final String sourceToken;

    public RecoveryControlService(
            OrderRecoverySourceClient order,
            WalletRecoverySourceClient wallet,
            MatchRecoverySourceClient match,
            RecoveryAuditStore auditStore,
            BrokerDeadLetterStore brokerDeadLetters,
            BrokerDeadLetterReplayCoordinator brokerReplay,
            @Value("${eap.recovery-control.source-token:}") String sourceToken) {
        this.sources = new LinkedHashMap<>();
        this.sources.put("eap-order", order);
        this.sources.put("eap-wallet", wallet);
        this.sources.put("eap-matchEngine", match);
        this.auditStore = auditStore;
        this.brokerDeadLetters = brokerDeadLetters;
        this.brokerReplay = brokerReplay;
        this.sourceToken = sourceToken;
    }

    public RecoveryCaseListResponse list(int requestedLimit) {
        requireSourceToken();
        int limit = Math.min(Math.max(requestedLimit, 1), MAX_LIMIT);
        List<ManagedRecoveryCase> cases = new ArrayList<>();
        List<RecoverySourceError> errors = new ArrayList<>();
        sources.forEach((service, client) -> {
            try {
                for (RecoveryCaseSummary summary : client.list(sourceToken, limit)) {
                    RecoveryAuditStore.CaseControlState state = auditStore.observe(summary);
                    cases.add(new ManagedRecoveryCase(summary, state.disposition(), state.lastObservedAt()));
                }
            } catch (RuntimeException failure) {
                errors.add(new RecoverySourceError(service, describe(failure)));
            }
        });
        try {
            for (RecoveryCaseSummary summary : brokerDeadLetters.list(limit)) {
                RecoveryAuditStore.CaseControlState state = auditStore.observe(summary);
                cases.add(new ManagedRecoveryCase(summary, state.disposition(), state.lastObservedAt()));
            }
        } catch (RuntimeException failure) {
            errors.add(new RecoverySourceError(BrokerDeadLetterStore.SERVICE, describe(failure)));
        }
        List<ManagedRecoveryCase> limited = cases.stream()
                .sorted(Comparator.comparing(item -> item.source().firstSeenAt()))
                .limit(limit)
                .toList();
        return new RecoveryCaseListResponse(Instant.now(), limited, errors);
    }

    public ManagedRecoveryCaseDetail detail(String caseId) {
        RecoveryCaseDetail detail = fetchDetail(caseId);
        RecoveryAuditStore.CaseControlState state = auditStore.observe(detail.summary());
        return managed(detail, state);
    }

    public RecoveryDryRunResult dryRun(String caseId, RecoveryDryRunRequest request) {
        RecoveryCaseDetail detail = fetchDetail(caseId);
        RecoveryAuditStore.CaseControlState controlState = auditStore.observe(detail.summary());
        if (request.action() == RecoveryActionType.REPLAY && !isBroker(caseId)) {
            return source(caseId).dryRun(sourceToken, caseId, request);
        }
        boolean fingerprintMatches = detail.summary().fingerprint().equals(request.expectedFingerprint());
        boolean actionAllowed = detail.summary().allowedActions().contains(request.action());
        if (request.action() == RecoveryActionType.REPLAY
                && isBroker(caseId)
                && fingerprintMatches
                && actionAllowed) {
            if (!"OPEN".equals(controlState.disposition())) {
                return new RecoveryDryRunResult(
                        caseId, request.action(), false,
                        "Broker case is already " + controlState.disposition(),
                        detail.summary().fingerprint(), detail.summary());
            }
            BrokerReplayPreflightResult preflight = brokerReplay.preflight(detail, sourceToken());
            boolean canExecuteSafely = preflight.safeToReplay()
                    || preflight.alreadyOwnedByConsumer();
            return new RecoveryDryRunResult(
                    caseId,
                    request.action(),
                    canExecuteSafely,
                    preflight.reason(),
                    detail.summary().fingerprint(),
                    detail.summary());
        }
        boolean allowed = fingerprintMatches && actionAllowed;
        return new RecoveryDryRunResult(
                caseId,
                request.action(),
                allowed,
                !fingerprintMatches
                        ? "Source changed after inspection; refresh the case before acting"
                        : actionAllowed
                                ? "Control-plane disposition is allowed; source business data will not be mutated"
                                : "Action is not allowed for this failure class",
                detail.summary().fingerprint(),
                detail.summary());
    }

    public RecoveryExecuteResult execute(
            String caseId,
            RecoveryControlActionRequest request,
            String operator) {
        String normalizedOperator = requireOperator(operator);
        RecoveryExecuteResult completed = auditStore.completed(caseId, request, normalizedOperator);
        if (completed != null) {
            return completed;
        }

        RecoveryCaseSummary prior = auditStore.pendingBefore(caseId, request, normalizedOperator);
        RecoveryCaseSummary before;
        if (prior == null) {
            RecoveryCaseDetail current = fetchDetail(caseId);
            before = current.summary();
            auditStore.observe(before);
        } else {
            before = prior;
        }
        RecoveryAuditStore.BeginAction begin = auditStore.begin(
                caseId, request, normalizedOperator, before);
        if (begin.completed()) {
            return begin.result();
        }

        try {
            RecoveryExecuteResult result;
            boolean fingerprintMatches = before.fingerprint().equals(request.expectedFingerprint());
            boolean actionAllowed = before.allowedActions().contains(request.action());
            if (!fingerprintMatches || !actionAllowed) {
                result = new RecoveryExecuteResult(
                        request.actionId(), caseId, request.action(), RecoveryExecutionStatus.REJECTED,
                        !fingerprintMatches
                                ? "Source changed after inspection; refresh the case before acting"
                                : "Action is not allowed for this failure class",
                        before, before);
            } else if (request.action() == RecoveryActionType.REPLAY && isBroker(caseId)) {
                result = executeBrokerReplay(caseId, request, normalizedOperator, before);
            } else if (request.action() == RecoveryActionType.REPLAY) {
                result = source(caseId).execute(sourceToken, caseId,
                        new RecoveryExecuteRequest(
                                request.actionId(), request.action(), request.expectedFingerprint()));
            } else {
                String disposition = request.action() == RecoveryActionType.PARK ? "PARKED" : "RESOLVED";
                boolean updated = auditStore.setDisposition(
                        caseId,
                        request.expectedFingerprint(),
                        disposition,
                        normalizedOperator,
                        request.reason());
                result = new RecoveryExecuteResult(
                        request.actionId(), caseId, request.action(),
                        updated ? RecoveryExecutionStatus.APPLIED : RecoveryExecutionStatus.NO_LONGER_ELIGIBLE,
                        updated
                                ? "Case disposition recorded; service-owned business data was not mutated"
                                : "Observed case changed before the disposition could be recorded",
                        before, before);
            }
            auditStore.complete(result);
            return result;
        } catch (RuntimeException failure) {
            auditStore.fail(request.actionId(), failure);
            throw failure;
        }
    }

    private RecoveryExecuteResult executeBrokerReplay(
            String caseId,
            RecoveryControlActionRequest request,
            String operator,
            RecoveryCaseSummary before) {
        RecoveryAuditStore.CaseControlState state = auditStore.state(caseId);
        if (!"OPEN".equals(state.disposition())) {
            return new RecoveryExecuteResult(
                    request.actionId(), caseId, request.action(), RecoveryExecutionStatus.ALREADY_APPLIED,
                    "Broker case is already " + state.disposition(), before, before);
        }

        RecoveryCaseDetail current = brokerDeadLetters.detail(caseId);
        if (!request.expectedFingerprint().equals(current.summary().fingerprint())) {
            return new RecoveryExecuteResult(
                    request.actionId(), caseId, request.action(),
                    RecoveryExecutionStatus.NO_LONGER_ELIGIBLE,
                    "Broker case changed after action start; refresh before replay",
                    before, current.summary());
        }

        BrokerReplayPreflightResult preflight = brokerReplay.execute(
                current, sourceToken(), request.actionId());
        RecoveryExecutionStatus status;
        String message;
        if (preflight.safeToReplay()) {
            status = RecoveryExecutionStatus.APPLIED;
            message = "Dead letter was confirmed by the owner queue after owner business-state preflight";
        } else if (preflight.alreadyOwnedByConsumer()) {
            status = RecoveryExecutionStatus.ALREADY_APPLIED;
            message = preflight.reason() + "; no broker publish was needed";
        } else {
            return new RecoveryExecuteResult(
                    request.actionId(), caseId, request.action(), RecoveryExecutionStatus.REJECTED,
                    preflight.reason(), before, current.summary());
        }

        auditStore.setDisposition(
                caseId,
                request.expectedFingerprint(),
                "RESOLVED",
                operator,
                request.reason());
        return new RecoveryExecuteResult(
                request.actionId(), caseId, request.action(), status, message, before, current.summary());
    }

    private RecoverySourceApi source(String caseId) {
        requireSourceToken();
        RecoveryCaseId.Parts parts = RecoveryCaseId.decode(caseId);
        RecoverySourceApi source = sources.get(parts.service());
        if (source == null) {
            throw new NoSuchElementException("Unknown recovery source service: " + parts.service());
        }
        return source;
    }

    private RecoveryCaseDetail fetchDetail(String caseId) {
        if (isBroker(caseId)) {
            return brokerDeadLetters.detail(caseId);
        }
        return source(caseId).detail(sourceToken(), caseId);
    }

    private boolean isBroker(String caseId) {
        RecoveryCaseId.Parts parts = RecoveryCaseId.decode(caseId);
        return BrokerDeadLetterStore.SERVICE.equals(parts.service())
                && parts.debtType() == com.eap.common.recovery.RecoveryDebtType.BROKER_DEAD_LETTER
                && BrokerDeadLetterStore.WORK.equals(parts.work());
    }

    private String sourceToken() {
        requireSourceToken();
        return sourceToken;
    }

    private void requireSourceToken() {
        if (sourceToken == null || sourceToken.isBlank()) {
            throw new IllegalStateException("Recovery source token is not configured");
        }
    }

    private String requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("X-EAP-Operator is required");
        }
        String normalized = operator.trim();
        if (normalized.length() > 120) {
            throw new IllegalArgumentException("X-EAP-Operator must not exceed 120 characters");
        }
        return normalized;
    }

    private ManagedRecoveryCaseDetail managed(
            RecoveryCaseDetail detail,
            RecoveryAuditStore.CaseControlState state) {
        return new ManagedRecoveryCaseDetail(
                detail,
                state.disposition(),
                state.reason(),
                state.operator(),
                state.dispositionAt(),
                state.lastObservedAt());
    }

    private String describe(RuntimeException failure) {
        String message = failure.getMessage();
        String description = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return description.length() <= 500 ? description : description.substring(0, 500);
    }
}
