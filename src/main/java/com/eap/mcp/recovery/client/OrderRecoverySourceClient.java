package com.eap.mcp.recovery.client;

import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(
        name = "order-recovery-source",
        contextId = "orderRecoverySourceClient",
        url = "${eap.order-service.base-url}")
public interface OrderRecoverySourceClient extends RecoverySourceApi {
}
