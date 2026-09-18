package com.eap.mcp.recovery.api;

import com.eap.common.recovery.RecoveryCaseSummary;

import java.time.Instant;

public record ManagedRecoveryCase(
        RecoveryCaseSummary source,
        String disposition,
        Instant lastObservedAt) {
}
