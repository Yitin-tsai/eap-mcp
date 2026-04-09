package com.eap.mcp.simulation.strategy;

import com.eap.mcp.simulation.model.MerchantStrategyType;

import java.util.EnumMap;
import java.util.Map;

/**
 * 策略工廠 - 根據類型獲取對應的策略實例
 */
public class StrategyFactory {

    private static final Map<MerchantStrategyType, MerchantStrategy> STRATEGIES = new EnumMap<>(MerchantStrategyType.class);

    static {
        STRATEGIES.put(MerchantStrategyType.CONSERVATIVE, new ConservativeStrategy());
        STRATEGIES.put(MerchantStrategyType.BALANCED, new BalancedStrategy());
        STRATEGIES.put(MerchantStrategyType.AGGRESSIVE, new AggressiveStrategy());
        STRATEGIES.put(MerchantStrategyType.TREND_FOLLOWER, new TrendFollowerStrategy());
        STRATEGIES.put(MerchantStrategyType.ARBITRAGE, new ArbitrageStrategy());
    }

    public static MerchantStrategy getStrategy(MerchantStrategyType type) {
        MerchantStrategy strategy = STRATEGIES.get(type);
        if (strategy == null) {
            return STRATEGIES.get(MerchantStrategyType.BALANCED); // 預設
        }
        return strategy;
    }

    /**
     * 隨機獲取一個策略 (用於模擬多元化市場)
     */
    public static MerchantStrategy getRandomStrategy(java.util.Random random) {
        MerchantStrategyType[] types = MerchantStrategyType.values();
        return STRATEGIES.get(types[random.nextInt(types.length)]);
    }

    /**
     * 根據權重分配策略 (模擬真實市場分佈)
     * - 保守型: 30%
     * - 平衡型: 35%
     * - 積極型: 20%
     * - 趨勢型: 10%
     * - 套利型: 5%
     */
    public static MerchantStrategy getWeightedRandomStrategy(java.util.Random random) {
        double roll = random.nextDouble();

        if (roll < 0.30) return STRATEGIES.get(MerchantStrategyType.CONSERVATIVE);
        if (roll < 0.65) return STRATEGIES.get(MerchantStrategyType.BALANCED);
        if (roll < 0.85) return STRATEGIES.get(MerchantStrategyType.AGGRESSIVE);
        if (roll < 0.95) return STRATEGIES.get(MerchantStrategyType.TREND_FOLLOWER);
        return STRATEGIES.get(MerchantStrategyType.ARBITRAGE);
    }
}
