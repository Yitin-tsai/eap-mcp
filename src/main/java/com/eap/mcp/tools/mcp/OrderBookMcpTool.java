package com.eap.mcp.tools.mcp;

import com.eap.common.dto.OrderBookResponseDto;
import com.eap.mcp.client.OrderServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 訂單簿工具
 * 使用 Spring AI @Tool 註解
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderBookMcpTool {

    private final OrderServiceClient orderServiceClient;

    @Tool(name = "getOrderBook", description = "獲取電力交易訂單簿數據，包含買賣盤資訊")
    public OrderBookResponseDto getOrderBook(
        @ToolParam(description = "訂單簿深度，預設為10層", required = false) Integer depth
    ) {
        try {
            // 設置默認值
            if (depth == null) {
                depth = 10;
            }
            
            log.info("獲取訂單簿，深度: {}", depth);
            
            ResponseEntity<OrderBookResponseDto> response = orderServiceClient.getOrderBook(depth);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                // 返回空的訂單簿
                return OrderBookResponseDto.builder()
                    .bids(List.of())
                    .asks(List.of())
                    .build();
            }
            
        } catch (Exception e) {
            log.error("獲取訂單簿失敗", e);
            // 返回空的訂單簿
            return OrderBookResponseDto.builder()
                .bids(List.of())
                .asks(List.of())
                .build();
        }
    }
}