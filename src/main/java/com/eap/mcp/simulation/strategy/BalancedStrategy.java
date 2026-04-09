package com.eap.mcp.simulation.strategy;

import com.eap.mcp.simulation.model.MerchantAgent;
import com.eap.mcp.simulation.model.TradeDecision;

import java.math.BigDecimal;
import java.util.Random;

/**
 * 平衡型策略
 * - 維持目標庫存水位
 * - 庫存低於目標: 買入補貨
 * - 庫存高於目標: 賣出多餘
 * - 考慮價格合理性
 */
public class BalancedStrategy implements MerchantStrategy {

    @Override
    public TradeDecision decide(MerchantAgent agent,
                                 BigDecimal bestBid,
                                 BigDecimal bestAsk,
                                 BigDecimal[] priceHistory,
                                 Random random) {

        double targetInventory = agent.getTargetInventory();
        double deviation = agent.getInventoryDeviation();
        double deviationPercent = deviation / targetInventory;

        // 計算平均歷史價格作為參考
        BigDecimal avgPrice = calculateAveragePrice(priceHistory, bestBid, bestAsk);

        // 庫存不足 (低於目標80%)
        if (deviationPercent < -0.2) {
            int neededQty = (int) Math.abs(deviation * 0.5); // 補一半差距
            neededQty = Math.max(1, neededQty);

            // 如果當前價格高於平均，減少買入量
            if (bestAsk.compareTo(avgPrice.multiply(BigDecimal.valueOf(1.1))) > 0) {
                neededQty = Math.max(1, neededQty / 2);
                return TradeDecision.buy(
                        bestAsk,
                        neededQty,
                        String.format("庫存不足%.0f%%，但價格偏高，謹慎買入", deviationPercent * -100),
                        0.6
                );
            }

            // 加入隨機性
            double noise = 1.0 + (random.nextDouble() - 0.5) * 0.3 * agent.getRiskTolerance();
            neededQty = (int) Math.ceil(neededQty * noise);

            return TradeDecision.buy(
                    bestAsk,
                    neededQty,
                    String.format("補充庫存：目前低於目標%.0f%%", deviationPercent * -100),
                    0.8
            );
        }

        // 庫存過剩 (高於目標120%)
        if (deviationPercent > 0.2) {
            int excessQty = (int) (deviation * 0.5); // 賣一半多餘的
            excessQty = Math.max(1, excessQty);

            // 如果當前價格低於平均，減少賣出量
            if (bestBid.compareTo(avgPrice.multiply(BigDecimal.valueOf(0.9))) < 0) {
                excessQty = Math.max(1, excessQty / 2);
                return TradeDecision.sell(
                        bestBid,
                        excessQty,
                        String.format("庫存過剩%.0f%%，但價格偏低，少量出售", deviationPercent * 100),
                        0.6
                );
            }

            // 加入隨機性
            double noise = 1.0 + (random.nextDouble() - 0.5) * 0.3 * agent.getRiskTolerance();
            excessQty = (int) Math.ceil(excessQty * noise);

            return TradeDecision.sell(
                    bestBid,
                    excessQty,
                    String.format("賣出多餘：庫存超過目標%.0f%%", deviationPercent * 100),
                    0.8
            );
        }

        return TradeDecision.hold(String.format("庫存平衡：目前%.0f%%目標水位", (1 + deviationPercent) * 100));
    }

    private BigDecimal calculateAveragePrice(BigDecimal[] priceHistory, BigDecimal bestBid, BigDecimal bestAsk) {
        if (priceHistory == null || priceHistory.length == 0) {
            return bestBid.add(bestAsk).divide(BigDecimal.valueOf(2));
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
            return bestBid.add(bestAsk).divide(BigDecimal.valueOf(2));
        }

        return sum.divide(BigDecimal.valueOf(count), java.math.RoundingMode.HALF_UP);
    }

    @Override
    public String getName() {
        return "平衡型";
    }

    @Override
    public String getDescription() {
        return "維持目標庫存水位，根據庫存偏差和價格合理性決定交易";
    }
}
