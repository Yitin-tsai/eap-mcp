package com.eap.mcp.recovery.api;

import com.eap.common.recovery.RecoveryActionType;
import com.eap.common.recovery.RecoveryExecuteResult;

import java.time.Instant;
import java.util.UUID;

public record RecoveryActionAuditView(
        UUID actionId,
        String caseId,
        RecoveryActionType action,
        String expectedFingerprint,
        String operator,
        String reason,
        String status,
        int attemptCount,
        Instant requestedAt,
        Instant lastAttemptAt,
        Instant completedAt,
        RecoveryExecuteResult result,
        String lastError) {
}
