package com.eap.mcp.simulation;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SimulationResult {
    private String symbol;
    private int steps;
    private List<String> events = new ArrayList<>();
    // records of actual PlaceOrderResponse objects returned by tradingTool
    private List<com.eap.common.dto.PlaceOrderResponse> fills = new ArrayList<>();
    // snapshots of MarketMetricsResponse after each real order
    private List<com.eap.common.dto.MarketMetricsResponse> marketSnapshots = new ArrayList<>();
    // structured parsed fills for reporting
    private List<SimulationFill> parsedFills = new ArrayList<>();
}
