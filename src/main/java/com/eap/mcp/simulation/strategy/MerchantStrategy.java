package com.eap.mcp.simulation.strategy;

import com.eap.mcp.simulation.model.MerchantAgent;
import com.eap.mcp.simulation.model.TradeDecision;

import java.math.BigDecimal;
import java.util.Random;

/**
 * 商家決策策略介面
 */
public interface MerchantStrategy {

    /**
     * 做出交易決策
     *
     * @param agent       商家代理
     * @param bestBid     當前最佳買價
     * @param bestAsk     當前最佳賣價
     * @param priceHistory 最近的價格歷史 (用於趨勢分析)
     * @param random      隨機數生成器 (加入噪音)
     * @return 交易決策
     */
    TradeDecision decide(MerchantAgent agent,
                         BigDecimal bestBid,
                         BigDecimal bestAsk,
                         BigDecimal[] priceHistory,
                         Random random);

    /**
     * 策略名稱
     */
    String getName();

    /**
     * 策略描述
     */
    String getDescription();
}
