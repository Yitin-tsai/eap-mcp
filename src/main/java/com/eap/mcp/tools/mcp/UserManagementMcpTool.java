package com.eap.mcp.tools.mcp;

import com.eap.mcp.client.WalletServiceClient;
import com.eap.common.dto.UserRegistrationResponse;
import com.eap.common.dto.WalletStatusResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class UserManagementMcpTool {

    @Autowired
    private WalletServiceClient walletServiceClient;

    
    @Tool(name = "registerUser", description = "註冊新用戶並創建錢包，新用戶將獲得 10000 電力和 10000 金額的初始餘額")
    public UserRegistrationResponse registerUser() {
        try {
            UserRegistrationResponse response = walletServiceClient.registerUser();
            
            if (response != null) {
                if (response.isSuccess()) {
                    log.info("用戶註冊成功: userId={}", response.getUserId());
                }
                return response;
            } else {
                return UserRegistrationResponse.failure("用戶註冊失敗: 服務未響應");
            }
            
        } catch (Exception e) {
            log.error("用戶註冊過程中發生異常", e);
            return UserRegistrationResponse.failure("用戶註冊失敗: " + e.getMessage());
        }
    }

    @Tool(name = "getUserWallet", description = "查詢指定用戶的錢包狀態，包括可用餘額和鎖定餘額")
    public WalletStatusResponse getUserWallet(@ToolParam(description = "用戶ID (UUID格式)", required = true) String userId) {
        try {
            UUID userUuid = UUID.fromString(userId);
            WalletStatusResponse wallet = walletServiceClient.getWalletStatus(userUuid);
            
            if (wallet != null) {
                return wallet;
            } else {
                return WalletStatusResponse.notFound(userUuid);
            }
            
        } catch (IllegalArgumentException e) {
            return WalletStatusResponse.notFound(UUID.randomUUID());
        } catch (Exception e) {
            log.error("查詢用戶錢包失敗", e);
            return WalletStatusResponse.notFound(UUID.randomUUID());
        }
    }

    @Tool(name = "checkUserExists", description = "檢查指定的用戶ID是否存在於系統中")
    public boolean checkUserExists(@ToolParam(description = "用戶ID (UUID格式)", required = true) String userId) {
        try {
            UUID userUuid = UUID.fromString(userId);
            log.info("檢查用戶存在性: userId={}", userUuid);
            
            WalletStatusResponse wallet = walletServiceClient.getWalletStatus(userUuid);
            boolean exists = wallet != null && wallet.isSuccess();
            
            log.info("用戶 {} 存在狀態: {}", userUuid, exists);
            return exists;
            
        } catch (IllegalArgumentException e) {
            log.error("無效的用戶ID格式: {}", userId);
            return false;
        } catch (Exception e) {
            log.error("檢查用戶存在性失敗", e);
            return false;
        }
    }
}
