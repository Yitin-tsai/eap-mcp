package com.eap.mcp.recovery.client;

import com.eap.common.recovery.BrokerReplayPreflightRequest;
import com.eap.common.recovery.BrokerReplayPreflightResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(
        name = "order-recovery-source",
        contextId = "orderRecoverySourceClient",
        url = "${eap.order-service.base-url}")
public interface OrderRecoverySourceClient extends RecoverySourceApi {

    @PostMapping("/internal/recovery/v1/broker-dead-letters/preflight")
    BrokerReplayPreflightResult brokerReplayPreflight(
            @RequestHeader(TOKEN_HEADER) String token,
            @RequestBody BrokerReplayPreflightRequest request);
}
