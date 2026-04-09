package com.eap.mcp.tools.mcp;

import com.eap.common.dto.PlaceOrderRequest;
import com.eap.common.dto.PlaceOrderResponse;
import com.eap.common.dto.CancelOrderResponse;
import com.eap.common.dto.UserOrdersResponse;
import com.eap.mcp.client.OrderServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * MCP 交易工具
 * 使用 Spring AI MCP 註解
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TradingMcpTool {

    private final OrderServiceClient orderServiceClient;

    @Tool(name = "placeOrder", description = "下單交易，支持買入和賣出訂單")
    public PlaceOrderResponse placeOrder(
        @ToolParam(description = "用戶ID，必須是有效的UUID格式", required = true) String userId,
        @ToolParam(description = "訂單方向：BUY 或 SELL", required = true) String side,
        @ToolParam(description = "訂單價格", required = true) String price,
        @ToolParam(description = "訂單數量", required = true) String qty,
        @ToolParam(description = "交易標的代碼", required = false) String symbol
    ) {
        try {
            // 設置默認值
            if (symbol == null || symbol.isEmpty()) {
                symbol = "ELC";
            }
            
            // 驗證參數
            if (!side.equals("BUY") && !side.equals("SELL")) {
                return PlaceOrderResponse.failure("side 參數必須是 'BUY' 或 'SELL'");
            }

            log.info("下單請求: userId={}, side={}, price={}, qty={}, symbol={}", 
                    userId, side, price, qty, symbol);

            PlaceOrderRequest orderRequest = PlaceOrderRequest.builder()
                .userId(userId)
                .side(side)
                .price(new java.math.BigDecimal(price))
                .qty(new java.math.BigDecimal(qty))
                .symbol(symbol)
                .build();

            ResponseEntity<PlaceOrderResponse> response = orderServiceClient.placeOrder(orderRequest);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                return PlaceOrderResponse.failure("訂單執行失敗，狀態碼: " + response.getStatusCode().value());
            }

        } catch (Exception e) {
            log.error("訂單執行失敗", e);
            return PlaceOrderResponse.failure("訂單執行失敗: " + e.getMessage());
        }
    }

    @Tool(name = "getUserOrders", description = "查詢用戶的所有交易訂單")
    public UserOrdersResponse getUserOrders(
        @ToolParam(description = "用戶ID，必須是有效的UUID格式", required = true) String userId
    ) {
        try {
            log.info("獲取用戶訂單，用戶: {}", userId);
            
            ResponseEntity<UserOrdersResponse> response = orderServiceClient.getUserOrders(userId, null);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                return UserOrdersResponse.failure(userId, "無法獲取用戶訂單，狀態碼: " + response.getStatusCode().value());
            }
            
        } catch (Exception e) {
            log.error("獲取用戶訂單失敗", e);
            return UserOrdersResponse.failure(userId, "獲取用戶訂單失敗: " + e.getMessage());
        }
    }

    @Tool(name = "cancelOrder", description = "取消指定的交易訂單")
    public CancelOrderResponse cancelOrder(
        @ToolParam(description = "要取消的訂單ID", required = true) String orderId
    ) {
        try {
            log.info("取消訂單: {}", orderId);
            
            ResponseEntity<CancelOrderResponse> response = orderServiceClient.cancelOrder(orderId);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                return CancelOrderResponse.failure(orderId, "取消訂單失敗，狀態碼: " + response.getStatusCode().value());
            }
            
        } catch (Exception e) {
            log.error("取消訂單失敗", e);
            return CancelOrderResponse.failure(orderId, "取消訂單失敗: " + e.getMessage());
        }
    }
}
