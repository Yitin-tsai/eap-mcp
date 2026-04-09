package com.eap.mcp.tools.mcp;

import com.eap.common.dto.AuctionBidRequest;
import com.eap.common.dto.AuctionBidResponse;
import com.eap.common.dto.AuctionResultDto;
import com.eap.common.dto.AuctionStatusDto;
import com.eap.mcp.client.OrderServiceClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 拍賣工具
 * 提供密封出價拍賣的 MCP Tool 介面
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuctionMcpTool {

    private final OrderServiceClient orderServiceClient;
    private final ObjectMapper objectMapper;

    @Tool(name = "submitAuctionBid", description = "Submit a sealed bid for the current auction session. Bids use stepwise format with price-amount pairs.")
    public AuctionBidResponse submitAuctionBid(
        @ToolParam(description = "用戶ID，必須是有效的UUID格式", required = true) String userId,
        @ToolParam(description = "投標方向：BUY 或 SELL", required = true) String side,
        @ToolParam(description = "投標階梯JSON陣列，格式如 '[{\"price\":60,\"amount\":50},{\"price\":55,\"amount\":30}]'", required = true) String stepsJson
    ) {
        try {
            // 驗證參數
            if (!side.equals("BUY") && !side.equals("SELL")) {
                return AuctionBidResponse.failure("side 參數必須是 'BUY' 或 'SELL'");
            }

            // 解析 stepsJson
            List<AuctionBidRequest.BidStep> steps = objectMapper.readValue(
                    stepsJson, new TypeReference<List<AuctionBidRequest.BidStep>>() {});

            log.info("提交拍賣出價: userId={}, side={}, steps={}", userId, side, steps.size());

            AuctionBidRequest request = AuctionBidRequest.builder()
                .userId(userId)
                .side(side)
                .steps(steps)
                .build();

            ResponseEntity<AuctionBidResponse> response = orderServiceClient.submitAuctionBid(request);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                return AuctionBidResponse.failure("拍賣出價提交失敗，狀態碼: " + response.getStatusCode().value());
            }

        } catch (Exception e) {
            log.error("拍賣出價提交失敗", e);
            return AuctionBidResponse.failure("拍賣出價提交失敗: " + e.getMessage());
        }
    }

    @Tool(name = "getAuctionStatus", description = "Get the current auction session status including participant count, open/close times, and price limits.")
    public AuctionStatusDto getAuctionStatus() {
        try {
            log.info("查詢拍賣狀態");

            ResponseEntity<AuctionStatusDto> response = orderServiceClient.getAuctionStatus();

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                return AuctionStatusDto.builder()
                    .status("UNKNOWN")
                    .build();
            }

        } catch (Exception e) {
            log.error("查詢拍賣狀態失敗", e);
            return AuctionStatusDto.builder()
                .status("ERROR")
                .build();
        }
    }

    @Tool(name = "getAuctionResults", description = "Get the clearing results for a specific auction session including clearing price, volume, and per-user allocations.")
    public AuctionResultDto getAuctionResults(
        @ToolParam(description = "拍賣場次ID，例如 'AUC-2026040910'", required = true) String auctionId
    ) {
        try {
            log.info("查詢拍賣結果: auctionId={}", auctionId);

            ResponseEntity<AuctionResultDto> response = orderServiceClient.getAuctionResults(auctionId);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                return AuctionResultDto.builder()
                    .auctionId(auctionId)
                    .build();
            }

        } catch (Exception e) {
            log.error("查詢拍賣結果失敗", e);
            return AuctionResultDto.builder()
                .auctionId(auctionId)
                .build();
        }
    }
}
