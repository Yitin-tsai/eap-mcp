package com.eap.mcp.simulation;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SimulationRequest {
    private String strategy = "simple";
    private String symbol = "ELC";
    private int steps = 10;
    private boolean executeReal = false; // if true, call placeOrder
    private String userId;

    // Configurable simulation params
    private double threshold = 0.01; // minimum spread to act on
    private int qty = 1; // units per order
    private String priceStrategy = "mid"; // topBid | mid | topAsk
    private String sides = "BOTH"; // BUY | SELL | BOTH
    private int ordersPerStep = 1; // how many orders per side each step
}
