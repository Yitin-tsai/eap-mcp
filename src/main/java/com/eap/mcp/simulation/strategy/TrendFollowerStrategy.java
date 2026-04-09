package com.eap.mcp.simulation.strategy;

import com.eap.mcp.simulation.model.MerchantAgent;
import com.eap.mcp.simulation.model.TradeDecision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

/**
 * 趨勢跟隨型策略
 * - 分析價格趨勢
 * - 價格上漲時買入 (預期繼續漲)
 * - 價格下跌時賣出 (預期繼續跌)
 * - 加入動量指標
 */
public class TrendFollowerStrategy implements MerchantStrategy {

    private static final int MIN_HISTORY_FOR_TREND = 3; // 至少需要3個價格點分析趨勢

    @Override
    public TradeDecision decide(MerchantAgent agent,
                                 BigDecimal bestBid,
                                 BigDecimal bestAsk,
                                 BigDecimal[] priceHistory,
                                 Random random) {

        double coverageHours = agent.getInventoryCoverageHours();

        // 庫存極低時強制買入
        if (coverageHours < 8) {
            int qty = (int) Math.ceil(agent.getHourlyConsumption() * 16);
            return TradeDecision.buy(bestAsk, qty, "庫存緊急，忽略趨勢強制買入", 1.0);
        }

        // 分析趨勢
        TrendAnalysis trend = analyzeTrend(priceHistory, bestBid, bestAsk);

        // 上漲趨勢 → 買入
        if (trend.direction > 0 && trend.strength > 0.3) {
            // 只有庫存還有空間時才買
            if (coverageHours < 72) {
                int baseQty = (int) (agent.getHourlyConsumption() * 4);
                int qty = (int) (baseQty * trend.strength * agent.getRiskTolerance());

                // 趨勢越強，信心越高
                double confidence = 0.5 + trend.strength * 0.4;

                // 加入隨機性
                qty = (int) (qty * (0.7 + random.nextDouble() * 0.6));
                qty = Math.max(1, qty);

                return TradeDecision.buy(
                        bestAsk,
                        qty,
                        String.format("上漲趨勢(+%.1f%%動量)，跟隨買入", trend.momentum * 100),
                        confidence
                );
            }
        }

        // 下跌趨勢 → 賣出
        if (trend.direction < 0 && trend.strength > 0.3) {
            // 只有有庫存可賣時才賣
            if (coverageHours > 16) {
                int availableToSell = (int) (agent.getInventory() - agent.getHourlyConsumption() * 16);
                int baseQty = (int) (agent.getHourlyConsumption() * 4);
                int qty = (int) Math.min(availableToSell, baseQty * trend.strength * agent.getRiskTolerance());

                double confidence = 0.5 + trend.strength * 0.4;

                // 加入隨機性
                qty = (int) (qty * (0.7 + random.nextDouble() * 0.6));
                qty = Math.max(1, qty);

                return TradeDecision.sell(
                        bestBid,
                        qty,
                        String.format("下跌趨勢(%.1f%%動量)，跟隨賣出", trend.momentum * 100),
                        confidence
                );
            }
        }

        // 盤整或趨勢不明確
        return TradeDecision.hold(String.format("趨勢不明確(強度%.0f%%)，觀望中", trend.strength * 100));
    }

    private TrendAnalysis analyzeTrend(BigDecimal[] priceHistory, BigDecimal bestBid, BigDecimal bestAsk) {
        TrendAnalysis result = new TrendAnalysis();

        if (priceHistory == null || priceHistory.length < MIN_HISTORY_FOR_TREND) {
            result.direction = 0;
            result.strength = 0;
            result.momentum = 0;
            return result;
        }

        // 計算簡單移動平均的變化
        int validCount = 0;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal firstValid = null;
        BigDecimal lastValid = null;

        for (BigDecimal price : priceHistory) {
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                if (firstValid == null) firstValid = price;
                lastValid = price;
                sum = sum.add(price);
                validCount++;
            }
        }

        if (validCount < MIN_HISTORY_FOR_TREND || firstValid == null || lastValid == null) {
            result.direction = 0;
            result.strength = 0;
            result.momentum = 0;
            return result;
        }

        // 計算動量 (最新價 vs 最舊價)
        BigDecimal currentMid = bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP);
        double momentum = (currentMid.doubleValue() - firstValid.doubleValue()) / firstValid.doubleValue();

        // 計算趨勢方向和強度
        result.direction = momentum > 0.01 ? 1 : (momentum < -0.01 ? -1 : 0);
        result.strength = Math.min(1.0, Math.abs(momentum) * 10); // 10%變化 = 100%強度
        result.momentum = momentum;

        return result;
    }

    private static class TrendAnalysis {
        int direction;    // 1=上漲, -1=下跌, 0=盤整
        double strength;  // 0-1 趨勢強度
        double momentum;  // 動量百分比
    }

    @Override
    public String getName() {
        return "趨勢跟隨型";
    }

    @Override
    public String getDescription() {
        return "分析價格趨勢，上漲時買入、下跌時賣出，追隨市場動能";
    }
}
