package com.eap.mcp.simulation.strategy;

import com.eap.mcp.simulation.model.MerchantAgent;
import com.eap.mcp.simulation.model.TradeDecision;

import java.math.BigDecimal;
import java.util.Random;

/**
 * 保守型策略
 * - 只在庫存極端情況下交易
 * - 庫存 < 8小時: 緊急買入
 * - 庫存 > 目標200%: 出清賣出
 */
public class ConservativeStrategy implements MerchantStrategy {

    @Override
    public TradeDecision decide(MerchantAgent agent,
                                 BigDecimal bestBid,
                                 BigDecimal bestAsk,
                                 BigDecimal[] priceHistory,
                                 Random random) {

        double coverageHours = agent.getInventoryCoverageHours();
        double targetInventory = agent.getTargetInventory();

        // 緊急買入：庫存不足8小時
        if (coverageHours < 8) {
            // 計算需要買多少才能達到24小時庫存
            int neededQty = (int) Math.ceil(agent.getHourlyConsumption() * 24 - agent.getInventory());
            neededQty = Math.max(1, neededQty);

            // 加入少量隨機性 (±10%)
            double noise = 1.0 + (random.nextDouble() - 0.5) * 0.2 * agent.getRiskTolerance();
            neededQty = (int) Math.ceil(neededQty * noise);

            return TradeDecision.buy(
                    bestAsk, // 用賣價買入 (市價單)
                    neededQty,
                    String.format("緊急補貨：庫存僅剩%.1f小時", coverageHours),
                    0.95
            );
        }

        // 出清賣出：庫存超過目標200%
        if (agent.getInventory() > targetInventory * 2.0) {
            // 賣出超過目標150%的部分
            int excessQty = (int) (agent.getInventory() - targetInventory * 1.5);
            excessQty = Math.max(1, excessQty);

            // 加入隨機性
            double noise = 1.0 + (random.nextDouble() - 0.5) * 0.2 * agent.getRiskTolerance();
            excessQty = (int) Math.ceil(excessQty * noise);

            return TradeDecision.sell(
                    bestBid, // 用買價賣出 (市價單)
                    excessQty,
                    String.format("出清存貨：庫存達目標%.0f%%", (agent.getInventory() / targetInventory) * 100),
                    0.9
            );
        }

        return TradeDecision.hold("庫存水位正常，暫不交易");
    }

    @Override
    public String getName() {
        return "保守型";
    }

    @Override
    public String getDescription() {
        return "只在庫存極端情況下才進行交易，避免頻繁操作";
    }
}
