package com.eap.mcp.simulation;

import com.eap.mcp.simulation.model.MerchantStrategyType;
import lombok.Data;

import java.util.List;

/**
 * 增強版模擬請求
 * 支援多商家、天氣變化、區域差異
 */
@Data
public class EnhancedSimulationRequest {

    // 基本設定
    private String symbol = "ELC";
    private int steps = 24;                 // 模擬步數 (每步代表1小時)
    private boolean executeReal = false;    // 是否執行真實下單

    // 商家設定
    private int merchantCount = 10;         // 商家數量
    private List<MerchantConfig> merchants; // 自訂商家配置 (可選)

    // 天氣和區域
    private boolean weatherEnabled = true;  // 是否啟用天氣影響
    private String defaultRegion = "CENTRAL"; // 預設區域

    // 隨機性
    private long randomSeed = 0;            // 隨機種子 (0=使用系統時間)
    private double noiseLevel = 0.2;        // 噪音程度 0-1

    // 初始市場狀態
    private double initialBidPrice = 95.0;
    private double initialAskPrice = 105.0;

    /**
     * 單個商家的配置
     */
    @Data
    public static class MerchantConfig {
        private String userId;
        private String name;
        private String region = "CENTRAL";
        private double inventory = 100;           // 初始庫存
        private double hourlyConsumption = 10;    // 每小時消耗
        private double productionCapacity = 8;    // 生產能力
        private MerchantStrategyType strategy = MerchantStrategyType.BALANCED;
        private double riskTolerance = 0.5;       // 風險偏好 0-1
        private double targetInventoryDays = 2;   // 目標庫存天數
    }
}
