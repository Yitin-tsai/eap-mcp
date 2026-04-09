package com.eap.mcp.tools.mcp;

import com.eap.common.dto.MarketMetricsResponse;
import com.eap.mcp.client.OrderServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * MCP 市場指標工具
 * 使用 Spring AI @Tool 註解
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarketMetricsMcpTool {

    private final OrderServiceClient orderServiceClient;

    @Tool(name = "getMarketMetrics", description = "獲取電力交易市場實時指標，包含價格、成交量等信息")
    public MarketMetricsResponse getMarketMetrics() {
        try {
            log.info("獲取市場指標");
            
            ResponseEntity<MarketMetricsResponse> response = orderServiceClient.getMarketMetrics(10);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                return MarketMetricsResponse.failure("無法獲取市場指標，狀態碼: " + response.getStatusCode().value());
            }
            
        } catch (Exception e) {
            log.error("獲取市場指標失敗", e);
            return MarketMetricsResponse.failure("獲取市場指標失敗: " + e.getMessage());
        }
    }
}