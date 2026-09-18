package com.eap.mcp.recovery.api;

import com.eap.common.recovery.RecoveryActionType;

import java.util.UUID;

public record RecoveryControlActionRequest(
        UUID actionId,
        RecoveryActionType action,
        String expectedFingerprint,
        String reason) {

    public RecoveryControlActionRequest {
        if (actionId == null || action == null) {
            throw new IllegalArgumentException("actionId and action are required");
        }
        if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
            throw new IllegalArgumentException("expectedFingerprint is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        reason = reason.trim();
        if (reason.length() > 1000) {
            throw new IllegalArgumentException("reason must not exceed 1000 characters");
        }
    }
}
