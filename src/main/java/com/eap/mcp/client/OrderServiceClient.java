package com.eap.mcp.client;

import com.eap.common.dto.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// ...existing code...

/**
 * Order Service Client for MCP
 * 調用 order-service 的 MCP API 端點
 */
@FeignClient(name = "order-service", url = "${eap.order-service.base-url}")
public interface OrderServiceClient {

    /**
     * 健康檢查
     */
    @GetMapping("/mcp/v1/health")
    ResponseEntity<HealthResponse> health();

    /**
     * 統一下單
     */
    @PostMapping("/mcp/v1/orders")
    ResponseEntity<PlaceOrderResponse> placeOrder(@RequestBody PlaceOrderRequest orderRequest);

    /**
     * 取消訂單
     */
    @DeleteMapping("/mcp/v1/orders/{orderId}")
    ResponseEntity<CancelOrderResponse> cancelOrder(@PathVariable String orderId);

    /**
     * 查詢用戶訂單
     */
    @GetMapping("/mcp/v1/orders")
    ResponseEntity<UserOrdersResponse> getUserOrders(
            @RequestParam String userId,
            @RequestParam(required = false) String status);

    /**
     * 獲取訂單簿
     */
    @GetMapping("/mcp/v1/orderbook")
    ResponseEntity<OrderBookResponseDto> getOrderBook(@RequestParam(defaultValue = "10") int depth);

    /**
     * 獲取市場指標
     */
    @GetMapping("/mcp/v1/metrics")
    ResponseEntity<MarketMetricsResponse> getMarketMetrics(@RequestParam(defaultValue = "10") int depth);
}
