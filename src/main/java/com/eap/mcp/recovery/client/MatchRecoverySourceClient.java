package com.eap.mcp.recovery.client;

import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(
        name = "match-recovery-source",
        contextId = "matchRecoverySourceClient",
        url = "${eap.match-engine.base-url}")
public interface MatchRecoverySourceClient extends RecoverySourceApi {
}
