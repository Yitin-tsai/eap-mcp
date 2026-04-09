package com.eap.mcp.simulation.model;

/**
 * 商家決策策略類型
 */
public enum MerchantStrategyType {

    /**
     * 保守型 - 只在庫存極端情況下交易
     * - 庫存 < 8小時: 緊急買入
     * - 庫存 > 目標200%: 出清賣出
     */
    CONSERVATIVE,

    /**
     * 平衡型 - 維持目標庫存
     * - 庫存低於目標: 買入補貨
     * - 庫存高於目標: 賣出多餘
     */
    BALANCED,

    /**
     * 積極型 - 頻繁交易，賺取價差
     * - 價格低時買入
     * - 價格高時賣出
     * - 同時考慮庫存
     */
    AGGRESSIVE,

    /**
     * 趨勢跟隨型 - 跟隨市場價格趨勢
     * - 價格上漲時買入 (預期繼續漲)
     * - 價格下跌時賣出 (預期繼續跌)
     */
    TREND_FOLLOWER,

    /**
     * 套利型 - 做市商策略
     * - 同時掛買賣單
     * - 賺取 bid-ask 價差
     */
    ARBITRAGE
}
