package com.eap.mcp.recovery;

import com.eap.common.recovery.RecoveryActionType;
import com.eap.common.recovery.RecoveryCaseDetail;
import com.eap.common.recovery.RecoveryCaseId;
import com.eap.common.recovery.RecoveryCaseSummary;
import com.eap.common.recovery.RecoveryDebtType;
import com.eap.common.recovery.RecoveryExecuteResult;
import com.eap.common.recovery.RecoveryExecutionStatus;
import com.eap.common.recovery.RecoveryFailureClass;
import com.eap.mcp.recovery.api.RecoveryControlActionRequest;
import com.eap.mcp.recovery.client.MatchRecoverySourceClient;
import com.eap.mcp.recovery.client.OrderRecoverySourceClient;
import com.eap.mcp.recovery.client.WalletRecoverySourceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryControlServiceTest {

    private OrderRecoverySourceClient order;
    private WalletRecoverySourceClient wallet;
    private MatchRecoverySourceClient match;
    private RecoveryAuditStore audit;
    private BrokerDeadLetterStore broker;
    private RecoveryControlService service;

    @BeforeEach
    void setUp() {
        order = mock(OrderRecoverySourceClient.class);
        wallet = mock(WalletRecoverySourceClient.class);
        match = mock(MatchRecoverySourceClient.class);
        audit = mock(RecoveryAuditStore.class);
        broker = mock(BrokerDeadLetterStore.class);
        service = new RecoveryControlService(order, wallet, match, audit, broker, "source-secret");
    }

    @Test
    void listShouldReturnHealthySourcesAndReportPartialFailure() {
        RecoveryCaseSummary caseSummary = summary(
                "eap-order", RecoveryDebtType.OUTBOX_TERMINAL, "event_outbox",
                "41", RecoveryFailureClass.TRANSIENT, EnumSet.allOf(RecoveryActionType.class));
        when(order.list("source-secret", 20)).thenReturn(List.of(caseSummary));
        when(wallet.list("source-secret", 20)).thenThrow(new IllegalStateException("wallet unavailable"));
        when(match.list("source-secret", 20)).thenReturn(List.of());
        when(broker.list(20)).thenReturn(List.of());
        when(audit.observe(caseSummary)).thenReturn(
                new RecoveryAuditStore.CaseControlState("OPEN", null, null, null, Instant.now()));

        var response = service.list(20);

        assertThat(response.cases()).hasSize(1);
        assertThat(response.cases().get(0).source()).isEqualTo(caseSummary);
        assertThat(response.sourceErrors()).singleElement()
                .satisfies(error -> {
                    assertThat(error.service()).isEqualTo("eap-wallet");
                    assertThat(error.error()).contains("wallet unavailable");
                });
    }

    @Test
    void permanentFailureShouldBeAuditedAndRejectedWithoutCallingSourceExecute() {
        RecoveryCaseSummary permanent = summary(
                "eap-wallet", RecoveryDebtType.INBOX_TERMINAL, "trade_executed_inbox",
                "trade-7", RecoveryFailureClass.INVARIANT,
                EnumSet.of(RecoveryActionType.PARK, RecoveryActionType.RESOLVE));
        String caseId = permanent.caseId();
        UUID actionId = UUID.randomUUID();
        RecoveryControlActionRequest request = new RecoveryControlActionRequest(
                actionId, RecoveryActionType.REPLAY, permanent.fingerprint(), "verify invariant first");
        when(audit.completed(caseId, request, "operator-a")).thenReturn(null);
        when(audit.pendingBefore(caseId, request, "operator-a")).thenReturn(null);
        when(wallet.detail("source-secret", caseId)).thenReturn(new RecoveryCaseDetail(permanent, "{}", Map.of()));
        when(audit.observe(permanent)).thenReturn(
                new RecoveryAuditStore.CaseControlState("OPEN", null, null, null, Instant.now()));
        when(audit.begin(caseId, request, "operator-a", permanent))
                .thenReturn(new RecoveryAuditStore.BeginAction(false, null));

        RecoveryExecuteResult result = service.execute(caseId, request, "operator-a");

        assertThat(result.status()).isEqualTo(RecoveryExecutionStatus.REJECTED);
        verify(wallet, never()).execute(any(), any(), any());
        verify(audit).complete(result);
    }

    @Test
    void ambiguousReplayRetryShouldUseSourceActionLedgerWithoutRefetchingDisappearedCase() {
        RecoveryCaseSummary before = summary(
                "eap-order", RecoveryDebtType.OUTBOX_TERMINAL, "event_outbox",
                "9", RecoveryFailureClass.TRANSIENT, EnumSet.allOf(RecoveryActionType.class));
        String caseId = before.caseId();
        UUID actionId = UUID.randomUUID();
        RecoveryControlActionRequest request = new RecoveryControlActionRequest(
                actionId, RecoveryActionType.REPLAY, before.fingerprint(), "retry lost response");
        RecoveryExecuteResult applied = new RecoveryExecuteResult(
                actionId, caseId, RecoveryActionType.REPLAY, RecoveryExecutionStatus.APPLIED,
                "already applied", before, null);
        when(audit.completed(caseId, request, "operator-a")).thenReturn(null);
        when(audit.pendingBefore(caseId, request, "operator-a")).thenReturn(before);
        when(audit.begin(caseId, request, "operator-a", before))
                .thenReturn(new RecoveryAuditStore.BeginAction(false, null));
        when(order.execute(eq("source-secret"), eq(caseId), any())).thenReturn(applied);

        RecoveryExecuteResult result = service.execute(caseId, request, "operator-a");

        assertThat(result).isEqualTo(applied);
        verify(order, never()).detail(any(), any());
        verify(audit).complete(applied);
    }

    private RecoveryCaseSummary summary(
            String owner,
            RecoveryDebtType debtType,
            String work,
            String sourceId,
            RecoveryFailureClass failureClass,
            java.util.Set<RecoveryActionType> actions) {
        String caseId = RecoveryCaseId.encode(owner, debtType, work, sourceId);
        Instant now = Instant.parse("2026-09-17T01:00:00Z");
        return new RecoveryCaseSummary(
                caseId, owner, debtType, work, sourceId, "FAILED", failureClass,
                3, now, now, "TEST_FAILURE", "failed", Map.of("id", sourceId),
                actions, "fingerprint-" + sourceId);
    }
}
