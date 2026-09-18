package com.eap.mcp.recovery;

import com.eap.common.recovery.RecoveryCaseSummary;
import com.eap.common.recovery.RecoveryExecuteResult;
import com.eap.mcp.recovery.api.RecoveryControlActionRequest;
import com.eap.mcp.recovery.api.RecoveryActionAuditView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Component
public class RecoveryAuditStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final int maxActionsPerMinute;

    public RecoveryAuditStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${eap.recovery-control.max-actions-per-operator-minute:10}") int maxActionsPerMinute) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.maxActionsPerMinute = Math.max(1, maxActionsPerMinute);
    }

    @Transactional
    public CaseControlState observe(RecoveryCaseSummary summary) {
        String json = write(summary);
        jdbc.update("""
                INSERT INTO recovery_control.recovery_cases
                    (case_id, service, debt_type, work, source_id,
                     fingerprint, snapshot_json, first_observed_at, last_observed_at)
                VALUES
                    (:caseId, :service, :debtType, :work, :sourceId,
                     :fingerprint, :snapshotJson, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (case_id) DO UPDATE
                SET service = EXCLUDED.service,
                    debt_type = EXCLUDED.debt_type,
                    work = EXCLUDED.work,
                    source_id = EXCLUDED.source_id,
                    snapshot_json = EXCLUDED.snapshot_json,
                    disposition = CASE
                        WHEN recovery_control.recovery_cases.fingerprint <> EXCLUDED.fingerprint
                            THEN 'OPEN'
                        ELSE recovery_control.recovery_cases.disposition
                    END,
                    disposition_reason = CASE
                        WHEN recovery_control.recovery_cases.fingerprint <> EXCLUDED.fingerprint
                            THEN NULL
                        ELSE recovery_control.recovery_cases.disposition_reason
                    END,
                    disposition_operator = CASE
                        WHEN recovery_control.recovery_cases.fingerprint <> EXCLUDED.fingerprint
                            THEN NULL
                        ELSE recovery_control.recovery_cases.disposition_operator
                    END,
                    disposition_at = CASE
                        WHEN recovery_control.recovery_cases.fingerprint <> EXCLUDED.fingerprint
                            THEN NULL
                        ELSE recovery_control.recovery_cases.disposition_at
                    END,
                    fingerprint = EXCLUDED.fingerprint,
                    last_observed_at = CURRENT_TIMESTAMP
                """, caseParams(summary).addValue("snapshotJson", json));
        return state(summary.caseId());
    }

    @Transactional(readOnly = true)
    public CaseControlState state(String caseId) {
        return jdbc.query("""
                SELECT disposition, disposition_reason, disposition_operator,
                       disposition_at, last_observed_at
                FROM recovery_control.recovery_cases
                WHERE case_id = :caseId
                """, Map.of("caseId", caseId), rs -> {
            if (!rs.next()) {
                return new CaseControlState("OPEN", null, null, null, Instant.now());
            }
            Timestamp dispositionAt = rs.getTimestamp("disposition_at");
            return new CaseControlState(
                    rs.getString("disposition"),
                    rs.getString("disposition_reason"),
                    rs.getString("disposition_operator"),
                    dispositionAt == null ? null : dispositionAt.toInstant(),
                    rs.getTimestamp("last_observed_at").toInstant());
        });
    }

    @Transactional
    public BeginAction begin(
            String caseId,
            RecoveryControlActionRequest request,
            String operator,
            RecoveryCaseSummary before) {
        lock("action:" + request.actionId());
        BeginAction existing = existing(caseId, request, operator);
        if (existing != null) {
            return existing;
        }

        lock("operator:" + operator);
        Integer recent = jdbc.queryForObject("""
                SELECT count(*)
                FROM recovery_control.recovery_actions
                WHERE operator_id = :operator
                  AND requested_at >= CURRENT_TIMESTAMP - INTERVAL '1 minute'
                """, Map.of("operator", operator), Integer.class);
        if (recent != null && recent >= maxActionsPerMinute) {
            throw new RecoveryRateLimitExceededException(
                    "Recovery action rate limit exceeded for operator " + operator);
        }

        jdbc.update("""
                INSERT INTO recovery_control.recovery_actions
                    (action_id, case_id, action_type, expected_fingerprint,
                     operator_id, reason, status, before_json)
                VALUES
                    (:actionId, :caseId, :actionType, :fingerprint,
                     :operator, :reason, 'STARTED', :beforeJson)
                """, actionParams(caseId, request, operator)
                .addValue("beforeJson", write(before)));
        return new BeginAction(false, null);
    }

    @Transactional(readOnly = true)
    public RecoveryExecuteResult completed(
            String caseId,
            RecoveryControlActionRequest request,
            String operator) {
        return jdbc.query("""
                SELECT case_id, action_type, expected_fingerprint, operator_id,
                       reason, result_json
                FROM recovery_control.recovery_actions
                WHERE action_id = :actionId
                """, Map.of("actionId", request.actionId()), rs -> {
            if (!rs.next()) {
                return null;
            }
            validateExisting(rs.getString("case_id"), rs.getString("action_type"),
                    rs.getString("expected_fingerprint"), rs.getString("operator_id"),
                    rs.getString("reason"), caseId, request, operator);
            String resultJson = rs.getString("result_json");
            return resultJson == null ? null : read(resultJson, RecoveryExecuteResult.class);
        });
    }

    @Transactional(readOnly = true)
    public RecoveryCaseSummary pendingBefore(
            String caseId,
            RecoveryControlActionRequest request,
            String operator) {
        return jdbc.query("""
                SELECT case_id, action_type, expected_fingerprint, operator_id,
                       reason, result_json, before_json
                FROM recovery_control.recovery_actions
                WHERE action_id = :actionId
                """, Map.of("actionId", request.actionId()), rs -> {
            if (!rs.next()) {
                return null;
            }
            validateExisting(rs.getString("case_id"), rs.getString("action_type"),
                    rs.getString("expected_fingerprint"), rs.getString("operator_id"),
                    rs.getString("reason"), caseId, request, operator);
            if (rs.getString("result_json") != null) {
                return null;
            }
            return read(rs.getString("before_json"), RecoveryCaseSummary.class);
        });
    }

    @Transactional
    public void complete(RecoveryExecuteResult result) {
        jdbc.update("""
                UPDATE recovery_control.recovery_actions
                SET status = :status,
                    completed_at = CURRENT_TIMESTAMP,
                    last_attempt_at = CURRENT_TIMESTAMP,
                    before_json = :beforeJson,
                    after_json = :afterJson,
                    result_json = :resultJson,
                    last_error = NULL
                WHERE action_id = :actionId
                """, new MapSqlParameterSource()
                .addValue("actionId", result.actionId())
                .addValue("status", result.status().name())
                .addValue("beforeJson", write(result.before()))
                .addValue("afterJson", result.after() == null ? null : write(result.after()))
                .addValue("resultJson", write(result)));
    }

    @Transactional
    public void fail(java.util.UUID actionId, RuntimeException failure) {
        jdbc.update("""
                UPDATE recovery_control.recovery_actions
                SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP,
                    last_attempt_at = CURRENT_TIMESTAMP, last_error = :lastError
                WHERE action_id = :actionId
                """, Map.of(
                "actionId", actionId,
                "lastError", truncate(failure.toString())));
    }

    @Transactional(readOnly = true)
    public RecoveryActionAuditView action(UUID actionId) {
        return jdbc.query("""
                SELECT action_id, case_id, action_type, expected_fingerprint,
                       operator_id, reason, status, attempt_count,
                       requested_at, last_attempt_at, completed_at,
                       result_json, last_error
                FROM recovery_control.recovery_actions
                WHERE action_id = :actionId
                """, Map.of("actionId", actionId), rs -> {
            if (!rs.next()) {
                throw new NoSuchElementException("Recovery action not found: " + actionId);
            }
            Timestamp completedAt = rs.getTimestamp("completed_at");
            String resultJson = rs.getString("result_json");
            return new RecoveryActionAuditView(
                    rs.getObject("action_id", UUID.class),
                    rs.getString("case_id"),
                    com.eap.common.recovery.RecoveryActionType.valueOf(rs.getString("action_type")),
                    rs.getString("expected_fingerprint"),
                    rs.getString("operator_id"),
                    rs.getString("reason"),
                    rs.getString("status"),
                    rs.getInt("attempt_count"),
                    rs.getTimestamp("requested_at").toInstant(),
                    rs.getTimestamp("last_attempt_at").toInstant(),
                    completedAt == null ? null : completedAt.toInstant(),
                    resultJson == null ? null : read(resultJson, RecoveryExecuteResult.class),
                    rs.getString("last_error"));
        });
    }

    @Transactional
    public boolean setDisposition(
            String caseId,
            String expectedFingerprint,
            String disposition,
            String operator,
            String reason) {
        return jdbc.update("""
                UPDATE recovery_control.recovery_cases
                SET disposition = :disposition,
                    disposition_reason = :reason,
                    disposition_operator = :operator,
                    disposition_at = CURRENT_TIMESTAMP
                WHERE case_id = :caseId AND fingerprint = :fingerprint
                """, new MapSqlParameterSource()
                .addValue("caseId", caseId)
                .addValue("fingerprint", expectedFingerprint)
                .addValue("disposition", disposition)
                .addValue("operator", operator)
                .addValue("reason", reason)) == 1;
    }

    private BeginAction existing(
            String caseId,
            RecoveryControlActionRequest request,
            String operator) {
        java.util.List<StoredAction> rows = jdbc.query("""
                SELECT case_id, action_type, expected_fingerprint, operator_id,
                       reason, status, result_json
                FROM recovery_control.recovery_actions
                WHERE action_id = :actionId
                FOR UPDATE
                """, Map.of("actionId", request.actionId()),
                (rs, rowNum) -> new StoredAction(
                        rs.getString("case_id"),
                        rs.getString("action_type"),
                        rs.getString("expected_fingerprint"),
                        rs.getString("operator_id"),
                        rs.getString("reason"),
                        rs.getString("result_json")));
        if (rows.isEmpty()) {
            return null;
        }
        StoredAction row = rows.get(0);
        validateExisting(row.caseId(), row.action(), row.fingerprint(), row.operator(), row.reason(),
                caseId, request, operator);
        if (row.resultJson() != null) {
            return new BeginAction(true, read(row.resultJson(), RecoveryExecuteResult.class));
        }
        jdbc.update("""
                UPDATE recovery_control.recovery_actions
                SET status = 'STARTED', attempt_count = attempt_count + 1,
                    last_attempt_at = CURRENT_TIMESTAMP, completed_at = NULL,
                    last_error = NULL
                WHERE action_id = :actionId
                """, Map.of("actionId", request.actionId()));
        return new BeginAction(false, null);
    }

    private void validateExisting(
            String storedCaseId,
            String storedAction,
            String storedFingerprint,
            String storedOperator,
            String storedReason,
            String caseId,
            RecoveryControlActionRequest request,
            String operator) {
        if (!caseId.equals(storedCaseId)
                || !request.action().name().equals(storedAction)
                || !request.expectedFingerprint().equals(storedFingerprint)
                || !operator.equals(storedOperator)
                || !request.reason().equals(storedReason)) {
            throw new IllegalArgumentException("Recovery actionId was already used for another request");
        }
    }

    private void lock(String identity) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(:identity))",
                Map.of("identity", identity), rs -> {
                    rs.next();
                    return Boolean.TRUE;
                });
    }

    private MapSqlParameterSource caseParams(RecoveryCaseSummary summary) {
        return new MapSqlParameterSource()
                .addValue("caseId", summary.caseId())
                .addValue("service", summary.service())
                .addValue("debtType", summary.debtType().name())
                .addValue("work", summary.work())
                .addValue("sourceId", summary.sourceId())
                .addValue("fingerprint", summary.fingerprint());
    }

    private MapSqlParameterSource actionParams(
            String caseId,
            RecoveryControlActionRequest request,
            String operator) {
        return new MapSqlParameterSource()
                .addValue("actionId", request.actionId())
                .addValue("caseId", caseId)
                .addValue("actionType", request.action().name())
                .addValue("fingerprint", request.expectedFingerprint())
                .addValue("operator", operator)
                .addValue("reason", request.reason());
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize recovery audit data", failure);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot deserialize recovery audit data", failure);
        }
    }

    private String truncate(String value) {
        return value == null || value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    public record CaseControlState(
            String disposition,
            String reason,
            String operator,
            Instant dispositionAt,
            Instant lastObservedAt) {
    }

    public record BeginAction(boolean completed, RecoveryExecuteResult result) {
    }

    private record StoredAction(
            String caseId,
            String action,
            String fingerprint,
            String operator,
            String reason,
            String resultJson) {
    }
}
