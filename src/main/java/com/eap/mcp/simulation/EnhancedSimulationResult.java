package com.eap.mcp.simulation;

import com.eap.mcp.simulation.model.WeatherCondition;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 增強版模擬結果
 */
@Data
public class EnhancedSimulationResult {

    private String symbol;
    private int totalSteps;
    private int merchantCount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    // 每步驟的詳細資料
    private List<StepResult> stepResults = new ArrayList<>();

    // 商家最終狀態
    private Map<String, MerchantFinalState> merchantFinalStates = new HashMap<>();

    // 市場統計
    private MarketStatistics marketStatistics = new MarketStatistics();

    // 事件日誌
    private List<String> events = new ArrayList<>();

    /**
     * 單步驟結果
     */
    @Data
    public static class StepResult {
        private int step;
        private int hourOfDay;
        private WeatherCondition weather;

        // 市場狀態
        private BigDecimal bestBid;
        private BigDecimal bestAsk;
        private BigDecimal spread;
        private BigDecimal midPrice;

        // 該步驟的交易
        private List<TradeRecord> trades = new ArrayList<>();

        // 總生產和消耗
        private double totalProduction;
        private double totalConsumption;
    }

    /**
     * 交易記錄
     */
    @Data
    public static class TradeRecord {
        private String merchantId;
        private String merchantName;
        private String action;          // BUY, SELL, HOLD, MARKET_MAKE
        private BigDecimal price;
        private int quantity;
        private String reason;
        private double confidence;
        private boolean executed;       // 是否真實執行
        private String executionResult;
    }

    /**
     * 商家最終狀態
     */
    @Data
    public static class MerchantFinalState {
        private String userId;
        private String name;
        private String strategy;
        private String region;
        private boolean isRealUser;         // 是否為真實註冊用戶

        // 庫存變化
        private double initialInventory;
        private double finalInventory;
        private double inventoryChange;

        // 生產與消耗統計
        private double totalProduction;
        private double totalConsumption;

        // 交易次數
        private int totalBuys;
        private int totalSells;
        private int totalHolds;

        // 交易金額
        private BigDecimal totalBuyValue;
        private BigDecimal totalSellValue;
        private BigDecimal averageBuyPrice;
        private BigDecimal averageSellPrice;

        // 財務狀況
        private BigDecimal initialBalance;
        private BigDecimal finalBalance;
        private BigDecimal balanceChange;

        // 獲利計算
        private BigDecimal netProfit;           // 純交易利潤 (賣出 - 買入)
        private BigDecimal inventoryValueChange; // 庫存價值變化
        private BigDecimal totalAssetChange;    // 總資產變化 (資金+庫存價值)

        // 風險與績效指標
        private double riskTolerance;
        private double targetInventoryDays;
    }

    /**
     * 市場統計
     */
    @Data
    public static class MarketStatistics {
        private BigDecimal highestPrice;
        private BigDecimal lowestPrice;
        private BigDecimal averagePrice;
        private BigDecimal priceVolatility;
        private int totalTrades;
        private int totalBuyOrders;
        private int totalSellOrders;
        private double totalVolume;
        private BigDecimal averageSpread;
    }

    public void addEvent(String event) {
        this.events.add(String.format("[Step %d] %s",
                stepResults.isEmpty() ? 0 : stepResults.size(),
                event));
    }
}
