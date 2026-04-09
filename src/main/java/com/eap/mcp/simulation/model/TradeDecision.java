package com.eap.mcp.simulation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 交易決策結果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeDecision {

    public enum Action {
        BUY,        // 買入
        SELL,       // 賣出
        HOLD,       // 觀望
        BUY_SELL    // 做市商：同時買賣
    }

    private Action action;
    private BigDecimal price;           // 期望價格
    private int quantity;               // 交易數量
    private String reason;              // 決策原因
    private double confidence;          // 信心度 0-1

    // 做市商模式下的額外欄位
    private BigDecimal bidPrice;        // 買價
    private BigDecimal askPrice;        // 賣價
    private int bidQuantity;
    private int askQuantity;

    public static TradeDecision hold(String reason) {
        return TradeDecision.builder()
                .action(Action.HOLD)
                .quantity(0)
                .reason(reason)
                .confidence(1.0)
                .build();
    }

    public static TradeDecision buy(BigDecimal price, int quantity, String reason, double confidence) {
        return TradeDecision.builder()
                .action(Action.BUY)
                .price(price)
                .quantity(quantity)
                .reason(reason)
                .confidence(confidence)
                .build();
    }

    public static TradeDecision sell(BigDecimal price, int quantity, String reason, double confidence) {
        return TradeDecision.builder()
                .action(Action.SELL)
                .price(price)
                .quantity(quantity)
                .reason(reason)
                .confidence(confidence)
                .build();
    }

    public static TradeDecision marketMake(BigDecimal bidPrice, int bidQty,
                                            BigDecimal askPrice, int askQty, String reason) {
        return TradeDecision.builder()
                .action(Action.BUY_SELL)
                .bidPrice(bidPrice)
                .bidQuantity(bidQty)
                .askPrice(askPrice)
                .askQuantity(askQty)
                .reason(reason)
                .confidence(0.8)
                .build();
    }
}
