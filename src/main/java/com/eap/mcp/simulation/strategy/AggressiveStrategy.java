package com.eap.mcp.simulation.strategy;

import com.eap.mcp.simulation.model.MerchantAgent;
import com.eap.mcp.simulation.model.TradeDecision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

/**
 * 積極型策略
 * - 頻繁交易，賺取價差
 * - 價格低時積極買入
 * - 價格高時積極賣出
 * - 同時考慮庫存限制
 */
public class AggressiveStrategy implements MerchantStrategy {

    private static final double PRICE_LOW_THRESHOLD = 0.95;  // 低於平均95%視為低價
    private static final double PRICE_HIGH_THRESHOLD = 1.05; // 高於平均105%視為高價

    @Override
    public TradeDecision decide(MerchantAgent agent,
                                 BigDecimal bestBid,
                                 BigDecimal bestAsk,
                                 BigDecimal[] priceHistory,
                                 Random random) {

        BigDecimal avgPrice = calculateAveragePrice(priceHistory, bestBid, bestAsk);
        BigDecimal midPrice = bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP);

        double priceRatio = midPrice.doubleValue() / avgPrice.doubleValue();
        double coverageHours = agent.getInventoryCoverageHours();
        double riskFactor = agent.getRiskTolerance();

        // 庫存極低時強制買入
        if (coverageHours < 6) {
            int qty = (int) Math.ceil(agent.getHourlyConsumption() * 12);
            return TradeDecision.buy(bestAsk, qty, "庫存緊急，強制買入", 1.0);
        }

        // 價格偏低 + 有空間存放 → 買入
        if (priceRatio < PRICE_LOW_THRESHOLD && coverageHours < 72) {
            // 價格越低，買越多
            double aggressiveness = (PRICE_LOW_THRESHOLD - priceRatio) / 0.1; // 每低1%增加10%購買量
            aggressiveness = Math.min(2.0, aggressiveness); // 最多2倍

            int baseQty = (int) (agent.getHourlyConsumption() * 6); // 基礎6小時量
            int qty = (int) (baseQty * (1 + aggressiveness * riskFactor));

            // 隨機性
            qty = (int) (qty * (0.8 + random.nextDouble() * 0.4));
            qty = Math.max(1, qty);

            return TradeDecision.buy(
                    bestAsk,
                    qty,
                    String.format("價格偏低(%.1f%%)，積極買入", (1 - priceRatio) * 100),
                    0.7 + aggressiveness * 0.1
            );
        }

        // 價格偏高 + 有庫存可賣 → 賣出
        if (priceRatio > PRICE_HIGH_THRESHOLD && coverageHours > 24) {
            // 價格越高，賣越多
            double aggressiveness = (priceRatio - PRICE_HIGH_THRESHOLD) / 0.1;
            aggressiveness = Math.min(2.0, aggressiveness);

            // 計算可賣數量 (保留至少24小時)
            int availableToSell = (int) (agent.getInventory() - agent.getHourlyConsumption() * 24);
            int baseQty = (int) (agent.getHourlyConsumption() * 4);
            int qty = (int) Math.min(availableToSell, baseQty * (1 + aggressiveness * riskFactor));

            // 隨機性
            qty = (int) (qty * (0.8 + random.nextDouble() * 0.4));
            qty = Math.max(1, qty);

            return TradeDecision.sell(
                    bestBid,
                    qty,
                    String.format("價格偏高(+%.1f%%)，積極賣出", (priceRatio - 1) * 100),
                    0.7 + aggressiveness * 0.1
            );
        }

        // 價格正常，根據庫存微調
        if (coverageHours > 48 && random.nextDouble() < 0.3 * riskFactor) {
            int qty = (int) (agent.getHourlyConsumption() * 2 * random.nextDouble());
            qty = Math.max(1, qty);
            return TradeDecision.sell(bestBid, qty, "價格正常，小量出售調整庫存", 0.5);
        }

        if (coverageHours < 36 && random.nextDouble() < 0.3 * riskFactor) {
            int qty = (int) (agent.getHourlyConsumption() * 2 * random.nextDouble());
            qty = Math.max(1, qty);
            return TradeDecision.buy(bestAsk, qty, "價格正常，小量買入補貨", 0.5);
        }

        return TradeDecision.hold("等待更好的交易機會");
    }

    private BigDecimal calculateAveragePrice(BigDecimal[] priceHistory, BigDecimal bestBid, BigDecimal bestAsk) {
        if (priceHistory == null || priceHistory.length == 0) {
            return bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP);
        }

        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (BigDecimal price : priceHistory) {
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                sum = sum.add(price);
                count++;
            }
        }

        if (count == 0) {
            return bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP);
        }

        return sum.divide(BigDecimal.valueOf(count), RoundingMode.HALF_UP);
    }

    @Override
    public String getName() {
        return "積極型";
    }

    @Override
    public String getDescription() {
        return "頻繁交易，在價格低時買入、價格高時賣出，同時考慮庫存限制";
    }
}
