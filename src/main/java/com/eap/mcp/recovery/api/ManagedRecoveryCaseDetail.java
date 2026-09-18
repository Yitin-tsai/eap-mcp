package com.eap.mcp.recovery.api;

import com.eap.common.recovery.RecoveryCaseDetail;

import java.time.Instant;

public record ManagedRecoveryCaseDetail(
        RecoveryCaseDetail source,
        String disposition,
        String dispositionReason,
        String dispositionOperator,
        Instant dispositionAt,
        Instant lastObservedAt) {
}
