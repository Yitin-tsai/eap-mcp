package com.eap.mcp.simulation;

import com.eap.mcp.tools.mcp.MarketMetricsMcpTool;
import com.eap.mcp.tools.mcp.TradingMcpTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimulationService {

    private final MarketMetricsMcpTool metricsTool;
    private final TradingMcpTool tradingTool;
    private volatile SimulationResult lastSimulationResult;

    public SimulationResult runSimulation(SimulationRequest req) {
        SimulationResult result = new SimulationResult();
        result.setSymbol(req.getSymbol());
        result.setSteps(req.getSteps());

    for (int step = 0; step < req.getSteps(); step++) {
            try {
                // fetch market metrics (preferred source for aggregated market data)
                var metrics = metricsTool.getMarketMetrics();

                BigDecimal topBid = BigDecimal.ZERO;
                BigDecimal topAsk = BigDecimal.ZERO;

                if (metrics != null && metrics.getOrderBook() != null) {
                    var orderBook = metrics.getOrderBook();
                    if (orderBook.getBids() != null && !orderBook.getBids().isEmpty() && orderBook.getBids().get(0) != null) {
                        topBid = BigDecimal.valueOf(orderBook.getBids().get(0).getPrice());
                    }
                    if (orderBook.getAsks() != null && !orderBook.getAsks().isEmpty() && orderBook.getAsks().get(0) != null) {
                        topAsk = BigDecimal.valueOf(orderBook.getAsks().get(0).getPrice());
                    }
                }

                result.getEvents().add("step:" + step + " bid:" + topBid + " ask:" + topAsk);

                // use configured parameters from the request
                BigDecimal threshold = BigDecimal.valueOf(req.getThreshold());
                int qty = req.getQty();
                String priceStrategy = req.getPriceStrategy();
                String sides = req.getSides();
                int ordersPerStep = req.getOrdersPerStep();

                if (topAsk.compareTo(BigDecimal.ZERO) > 0 && topBid.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal spread = topAsk.subtract(topBid);
                    if (topAsk.compareTo(topBid) > 0 && spread.compareTo(threshold) > 0) {
                        for (int orderIndex = 0; orderIndex < Math.max(1, ordersPerStep); orderIndex++) {
                            // BUY
                            if ("BUY".equalsIgnoreCase(sides) || "BOTH".equalsIgnoreCase(sides)) {
                                BigDecimal selected = selectPrice(topBid, topAsk, priceStrategy);
                                String price = selected.toPlainString();
                                String qtyStr = String.valueOf(qty);
                                if (req.isExecuteReal()) {
                                    var orderResponse = tradingTool.placeOrder(req.getUserId(), "BUY", price, qtyStr, req.getSymbol());
                                    result.getEvents().add("placed real BUY: " + orderResponse.getMessage());
                                    result.getFills().add(orderResponse);
                                    // after real order, re-fetch market metrics and store snapshot
                                    try {
                                        var postSnapshot = metricsTool.getMarketMetrics();
                                        // previous snapshot (if any)
                                        com.eap.common.dto.MarketMetricsResponse previousSnapshot = null;
                                        if (!result.getMarketSnapshots().isEmpty()) {
                                            previousSnapshot = result.getMarketSnapshots().get(result.getMarketSnapshots().size() - 1);
                                        }
                                        result.getMarketSnapshots().add(postSnapshot);

                                        // parse structured fill
                                        SimulationFill simulationFill = new SimulationFill();
                                        simulationFill.setOrderId(orderResponse.getOrderId());
                                        simulationFill.setSide(orderResponse.getSide());
                                        simulationFill.setExecutedPrice(orderResponse.getPrice());
                                        simulationFill.setExecutedQty(orderResponse.getQty());
                                        simulationFill.setStatus(orderResponse.getStatus());
                                        simulationFill.setMessage(orderResponse.getMessage());
                                        simulationFill.setSymbol(orderResponse.getSymbol());
                                        simulationFill.setStep(step);
                                        simulationFill.setTimestamp(orderResponse.getAcceptedAt());
                                        if (previousSnapshot != null && previousSnapshot.getOrderBook() != null) {
                                            var previousOrderBook = previousSnapshot.getOrderBook();
                                            if (previousOrderBook.getBids() != null && !previousOrderBook.getBids().isEmpty() && previousOrderBook.getBids().get(0) != null) {
                                                simulationFill.setPreBestBid(BigDecimal.valueOf(previousOrderBook.getBids().get(0).getPrice()));
                                            }
                                            if (previousOrderBook.getAsks() != null && !previousOrderBook.getAsks().isEmpty() && previousOrderBook.getAsks().get(0) != null) {
                                                simulationFill.setPreBestAsk(BigDecimal.valueOf(previousOrderBook.getAsks().get(0).getPrice()));
                                            }
                                        }
                                        if (postSnapshot != null && postSnapshot.getOrderBook() != null) {
                                            var postOrderBook = postSnapshot.getOrderBook();
                                            if (postOrderBook.getBids() != null && !postOrderBook.getBids().isEmpty() && postOrderBook.getBids().get(0) != null) {
                                                simulationFill.setPostBestBid(BigDecimal.valueOf(postOrderBook.getBids().get(0).getPrice()));
                                            }
                                            if (postOrderBook.getAsks() != null && !postOrderBook.getAsks().isEmpty() && postOrderBook.getAsks().get(0) != null) {
                                                simulationFill.setPostBestAsk(BigDecimal.valueOf(postOrderBook.getAsks().get(0).getPrice()));
                                            }
                                        }
                                        result.getParsedFills().add(simulationFill);
                                    } catch (Exception e) {
                                        result.getEvents().add("snapshot error:" + e.getMessage());
                                    }
                                } else {
                                    result.getEvents().add("simulated BUY: user=" + req.getUserId() + " " + price + "x" + qtyStr);
                                }
                            }

                            // SELL
                            if ("SELL".equalsIgnoreCase(sides) || "BOTH".equalsIgnoreCase(sides)) {
                                BigDecimal selected = selectPrice(topBid, topAsk, priceStrategy);
                                String price = selected.toPlainString();
                                String qtyStr = String.valueOf(qty);
                                if (req.isExecuteReal()) {
                                    var orderResponse = tradingTool.placeOrder(req.getUserId(), "SELL", price, qtyStr, req.getSymbol());
                                    result.getEvents().add("placed real SELL: " + orderResponse.getMessage());
                                    result.getFills().add(orderResponse);
                                    try {
                                        var postSnapshot = metricsTool.getMarketMetrics();
                                        result.getMarketSnapshots().add(postSnapshot);
                                    } catch (Exception e) {
                                        result.getEvents().add("snapshot error:" + e.getMessage());
                                    }
                                } else {
                                    result.getEvents().add("simulated SELL: user=" + req.getUserId() + " " + price + "x" + qtyStr);
                                }
                            }
                        }
                    }
                }

            } catch (Exception e) {
                log.error("simulation step failed", e);
                result.getEvents().add("error:" + e.getMessage());
            }
        }

        // cache the last result for export/reporting
        this.lastSimulationResult = result;
        return result;
    }

    /**
     * Return the most recent SimulationResult, or null if none run yet.
     */
    public SimulationResult getLastSimulationResult() {
        return lastSimulationResult;
    }

    private BigDecimal selectPrice(BigDecimal topBid, BigDecimal topAsk, String strategy) {
        if ("topBid".equalsIgnoreCase(strategy) || "bid".equalsIgnoreCase(strategy)) {
            return topBid;
        }
        if ("topAsk".equalsIgnoreCase(strategy) || "ask".equalsIgnoreCase(strategy)) {
            return topAsk;
        }
        // default: mid price
        try {
            return topBid.add(topAsk).divide(BigDecimal.valueOf(2));
        } catch (Exception e) {
            return topBid;
        }
    }
}
