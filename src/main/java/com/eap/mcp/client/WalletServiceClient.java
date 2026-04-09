package com.eap.mcp.client;

import com.eap.common.dto.UserRegistrationResponse;
import com.eap.common.dto.WalletStatusResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;
import java.util.UUID;

/**
 * Wallet Service Feign client for MCP
 */
@FeignClient(name = "wallet-service", url = "${eap.wallet.base-url:http://localhost:8081/eap-wallet}")
public interface WalletServiceClient {

    /**
     * 註冊新用戶並創建錢包
     */
    @PostMapping("/v1/wallet/register")
    UserRegistrationResponse registerUser();

    /**
     * 查詢用戶錢包狀態
     */
    @GetMapping("/v1/wallet/status/{userId}")
    WalletStatusResponse getWalletStatus(@PathVariable("userId") UUID userId);

    /**
     * 檢查用戶是否存在
     */
    @GetMapping("/v1/wallet/exists/{userId}")
    Boolean checkUserExists(@PathVariable("userId") UUID userId);

    /**
     * 獲取已註冊用戶列表
     */
    @GetMapping("/v1/wallet/users")
    List<UUID> listUsers(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "10") int limit);
}
