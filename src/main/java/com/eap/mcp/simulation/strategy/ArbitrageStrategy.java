package com.eap.mcp.simulation.strategy;

import com.eap.mcp.simulation.model.MerchantAgent;
import com.eap.mcp.simulation.model.TradeDecision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

/**
 * 套利/做市商型策略
 * - 同時掛買賣單
 * - 賺取 bid-ask 價差
 * - 維持市場流動性
 */
public class ArbitrageStrategy implements MerchantStrategy {

    private static final double MIN_SPREAD_PERCENT = 0.02; // 最小價差2%才有利可圖

    @Override
    public TradeDecision decide(MerchantAgent agent,
                                 BigDecimal bestBid,
                                 BigDecimal bestAsk,
                                 BigDecimal[] priceHistory,
                                 Random random) {

        double coverageHours = agent.getInventoryCoverageHours();

        // 庫存極端情況，優先處理
        if (coverageHours < 8) {
            int qty = (int) Math.ceil(agent.getHourlyConsumption() * 12);
            return TradeDecision.buy(bestAsk, qty, "庫存緊急，暫停做市，優先補貨", 1.0);
        }

        if (coverageHours > 120) {
            int qty = (int) (agent.getInventory() - agent.getHourlyConsumption() * 72);
            return TradeDecision.sell(bestBid, qty, "庫存過高，暫停做市，優先出清", 1.0);
        }

        // 計算價差
        BigDecimal spread = bestAsk.subtract(bestBid);
        BigDecimal midPrice = bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP);
        double spreadPercent = spread.doubleValue() / midPrice.doubleValue();

        // 價差太小，不值得做市
        if (spreadPercent < MIN_SPREAD_PERCENT) {
            return TradeDecision.hold(String.format("價差太小(%.2f%%)，等待機會", spreadPercent * 100));
        }

        // 計算做市價格 (在 bid-ask 之間)
        // 買價略高於 bestBid，賣價略低於 bestAsk
        BigDecimal spreadAmount = spread.multiply(BigDecimal.valueOf(0.2)); // 縮小20%價差
        BigDecimal myBidPrice = bestBid.add(spreadAmount);
        BigDecimal myAskPrice = bestAsk.subtract(spreadAmount);

        // 計算數量 (根據庫存和風險偏好)
        int baseQty = (int) (agent.getHourlyConsumption() * 2);

        // 庫存偏低時，買單多一些
        // 庫存偏高時，賣單多一些
        double inventoryBias = (coverageHours - 48) / 48.0; // 48小時為中性
        inventoryBias = Math.max(-0.5, Math.min(0.5, inventoryBias));

        int bidQty = (int) (baseQty * (1 - inventoryBias) * agent.getRiskTolerance());
        int askQty = (int) (baseQty * (1 + inventoryBias) * agent.getRiskTolerance());

        // 加入隨機性
        bidQty = (int) (bidQty * (0.8 + random.nextDouble() * 0.4));
        askQty = (int) (askQty * (0.8 + random.nextDouble() * 0.4));

        bidQty = Math.max(1, bidQty);
        askQty = Math.max(1, askQty);

        // 確保賣出數量不超過可用庫存
        int maxSellQty = (int) (agent.getInventory() - agent.getHourlyConsumption() * 12);
        askQty = Math.min(askQty, Math.max(0, maxSellQty));

        if (askQty <= 0) {
            // 庫存不足以做市賣出，只掛買單
            return TradeDecision.buy(
                    myBidPrice,
                    bidQty,
                    String.format("庫存不足做市，僅掛買單@%.2f", myBidPrice.doubleValue()),
                    0.7
            );
        }

        return TradeDecision.marketMake(
                myBidPrice, bidQty,
                myAskPrice, askQty,
                String.format("做市中: 買@%.2f x%d, 賣@%.2f x%d, 價差%.2f%%",
                        myBidPrice.doubleValue(), bidQty,
                        myAskPrice.doubleValue(), askQty,
                        spreadPercent * 100)
        );
    }

    @Override
    public String getName() {
        return "套利/做市商型";
    }

    @Override
    public String getDescription() {
        return "同時掛買賣單賺取價差，提供市場流動性";
    }
}
