package com.eap.mcp.recovery.api;

import java.time.Instant;
import java.util.List;

public record RecoveryCaseListResponse(
        Instant observedAt,
        List<ManagedRecoveryCase> cases,
        List<RecoverySourceError> sourceErrors) {

    public RecoveryCaseListResponse {
        cases = List.copyOf(cases);
        sourceErrors = List.copyOf(sourceErrors);
    }
}
