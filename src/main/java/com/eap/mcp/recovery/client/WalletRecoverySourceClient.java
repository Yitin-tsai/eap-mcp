package com.eap.mcp.recovery.client;

import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(
        name = "wallet-recovery-source",
        contextId = "walletRecoverySourceClient",
        url = "${eap.wallet.base-url}")
public interface WalletRecoverySourceClient extends RecoverySourceApi {
}
