package com.eap.mcp.simulation;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SimulationFill {
    private String orderId;
    private String side;
    private BigDecimal executedPrice;
    private BigDecimal executedQty;
    private String status;
    private String message;
    private String symbol;
    private int step;
    private LocalDateTime timestamp;

    // market before/after
    private BigDecimal preBestBid;
    private BigDecimal preBestAsk;
    private BigDecimal postBestBid;
    private BigDecimal postBestAsk;
}
