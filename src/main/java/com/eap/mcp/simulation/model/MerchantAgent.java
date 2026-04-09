package com.eap.mcp.simulation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 商家代理模型
 * 每個商家都有電量存量、消耗量、生產能力
 * 並追蹤所有交易記錄以計算獲利
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantAgent {

    private String userId;
    private String name;
    private String region;              // 所在區域 (影響天氣)
    private boolean isRealUser;         // 是否為真實註冊用戶

    // 電量相關
    private double inventory;           // 當前電量存量 (kWh)
    private double initialInventory;    // 初始電量存量 (用於計算變化)
    private double hourlyConsumption;   // 每小時消耗量 (kWh)
    private double productionCapacity;  // 生產能力上限 (kWh) - 太陽能/風能等

    // 決策參數
    private MerchantStrategyType strategyType;  // 決策策略類型
    private double riskTolerance;       // 風險偏好 0.0-1.0 (0=保守, 1=激進)
    private double targetInventoryDays; // 目標庫存天數 (例如想維持2天的存量)

    // 財務相關
    private BigDecimal balance;         // 資金餘額
    private BigDecimal initialBalance;  // 初始資金餘額

    // ========== 交易追蹤 ==========
    @Builder.Default
    private List<TradeExecution> tradeHistory = new ArrayList<>();

    @Builder.Default
    private int buyCount = 0;           // 買入次數
    @Builder.Default
    private int sellCount = 0;          // 賣出次數
    @Builder.Default
    private int holdCount = 0;          // 觀望次數

    @Builder.Default
    private BigDecimal totalBuyValue = BigDecimal.ZERO;    // 總買入金額
    @Builder.Default
    private BigDecimal totalSellValue = BigDecimal.ZERO;   // 總賣出金額
    @Builder.Default
    private double totalBuyQuantity = 0;   // 總買入數量
    @Builder.Default
    private double totalSellQuantity = 0;  // 總賣出數量

    // 生產和消耗追蹤
    @Builder.Default
    private double totalProduction = 0;    // 總生產量
    @Builder.Default
    private double totalConsumption = 0;   // 總消耗量

    /**
     * 交易執行記錄
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TradeExecution {
        private int step;
        private String action;          // BUY, SELL, HOLD
        private BigDecimal price;
        private int quantity;
        private BigDecimal value;       // price * quantity
        private String reason;
        private boolean executed;
        private double inventoryBefore;
        private double inventoryAfter;
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;
    }

    /**
     * 計算目標庫存量
     */
    public double getTargetInventory() {
        return hourlyConsumption * 24 * targetInventoryDays;
    }

    /**
     * 計算庫存偏差 (正值=庫存過多, 負值=庫存不足)
     */
    public double getInventoryDeviation() {
        return inventory - getTargetInventory();
    }

    /**
     * 計算庫存覆蓋小時數
     */
    public double getInventoryCoverageHours() {
        if (hourlyConsumption <= 0) return Double.MAX_VALUE;
        return inventory / hourlyConsumption;
    }

    /**
     * 是否庫存緊張 (少於12小時)
     */
    public boolean isInventoryLow() {
        return getInventoryCoverageHours() < 12;
    }

    /**
     * 是否庫存過剩 (超過目標的150%)
     */
    public boolean isInventoryHigh() {
        return inventory > getTargetInventory() * 1.5;
    }

    /**
     * 更新庫存 (消耗 + 生產)
     */
    public void updateInventory(double production, double consumption) {
        this.totalProduction += production;
        this.totalConsumption += consumption;
        this.inventory = Math.max(0, this.inventory - consumption + production);
    }

    /**
     * 更新庫存 (僅生產，消耗使用預設)
     */
    public void updateInventory(double production) {
        updateInventory(production, hourlyConsumption);
    }

    /**
     * 記錄初始狀態
     */
    public void recordInitialState() {
        this.initialInventory = this.inventory;
        this.initialBalance = this.balance;
    }

    /**
     * 執行交易並記錄
     */
    public void executeTrade(int step, TradeDecision decision, boolean actuallyExecuted) {
        double inventoryBefore = this.inventory;
        BigDecimal balanceBefore = this.balance;

        BigDecimal tradeValue = BigDecimal.ZERO;
        if (decision.getPrice() != null && decision.getQuantity() > 0) {
            tradeValue = decision.getPrice().multiply(BigDecimal.valueOf(decision.getQuantity()));
        }

        switch (decision.getAction()) {
            case BUY:
                buyCount++;
                if (actuallyExecuted) {
                    this.inventory += decision.getQuantity();
                    this.balance = this.balance.subtract(tradeValue);
                    this.totalBuyValue = this.totalBuyValue.add(tradeValue);
                    this.totalBuyQuantity += decision.getQuantity();
                }
                break;

            case SELL:
                sellCount++;
                if (actuallyExecuted) {
                    this.inventory = Math.max(0, this.inventory - decision.getQuantity());
                    this.balance = this.balance.add(tradeValue);
                    this.totalSellValue = this.totalSellValue.add(tradeValue);
                    this.totalSellQuantity += decision.getQuantity();
                }
                break;

            case BUY_SELL:
                // 做市商：同時買賣
                buyCount++;
                sellCount++;
                if (actuallyExecuted) {
                    // 買入部分
                    if (decision.getBidPrice() != null && decision.getBidQuantity() > 0) {
                        BigDecimal buyValue = decision.getBidPrice()
                                .multiply(BigDecimal.valueOf(decision.getBidQuantity()));
                        this.inventory += decision.getBidQuantity();
                        this.balance = this.balance.subtract(buyValue);
                        this.totalBuyValue = this.totalBuyValue.add(buyValue);
                        this.totalBuyQuantity += decision.getBidQuantity();
                    }
                    // 賣出部分
                    if (decision.getAskPrice() != null && decision.getAskQuantity() > 0) {
                        BigDecimal sellValue = decision.getAskPrice()
                                .multiply(BigDecimal.valueOf(decision.getAskQuantity()));
                        this.inventory = Math.max(0, this.inventory - decision.getAskQuantity());
                        this.balance = this.balance.add(sellValue);
                        this.totalSellValue = this.totalSellValue.add(sellValue);
                        this.totalSellQuantity += decision.getAskQuantity();
                    }
                }
                break;

            case HOLD:
            default:
                holdCount++;
                break;
        }

        // 記錄交易歷史
        tradeHistory.add(TradeExecution.builder()
                .step(step)
                .action(decision.getAction().name())
                .price(decision.getPrice())
                .quantity(decision.getQuantity())
                .value(tradeValue)
                .reason(decision.getReason())
                .executed(actuallyExecuted)
                .inventoryBefore(inventoryBefore)
                .inventoryAfter(this.inventory)
                .balanceBefore(balanceBefore)
                .balanceAfter(this.balance)
                .build());
    }

    /**
     * 簡化的交易執行（向後兼容）
     */
    public void executeTrade(double quantity, boolean isBuy) {
        if (isBuy) {
            this.inventory += quantity;
            this.totalBuyQuantity += quantity;
            buyCount++;
        } else {
            this.inventory = Math.max(0, this.inventory - quantity);
            this.totalSellQuantity += quantity;
            sellCount++;
        }
    }

    /**
     * 計算淨獲利 (賣出收入 - 買入成本)
     */
    public BigDecimal getNetProfit() {
        return totalSellValue.subtract(totalBuyValue);
    }

    /**
     * 計算庫存價值變化 (使用平均價格估算)
     */
    public BigDecimal getInventoryValueChange(BigDecimal averagePrice) {
        double inventoryChange = inventory - initialInventory;
        return averagePrice.multiply(BigDecimal.valueOf(inventoryChange));
    }

    /**
     * 計算總資產變化 (資金變化 + 庫存價值變化)
     */
    public BigDecimal getTotalAssetChange(BigDecimal averagePrice) {
        BigDecimal balanceChange = balance.subtract(initialBalance);
        BigDecimal inventoryValueChange = getInventoryValueChange(averagePrice);
        return balanceChange.add(inventoryValueChange);
    }

    /**
     * 獲取平均買入價格
     */
    public BigDecimal getAverageBuyPrice() {
        if (totalBuyQuantity <= 0) return BigDecimal.ZERO;
        return totalBuyValue.divide(BigDecimal.valueOf(totalBuyQuantity), 2, RoundingMode.HALF_UP);
    }

    /**
     * 獲取平均賣出價格
     */
    public BigDecimal getAverageSellPrice() {
        if (totalSellQuantity <= 0) return BigDecimal.ZERO;
        return totalSellValue.divide(BigDecimal.valueOf(totalSellQuantity), 2, RoundingMode.HALF_UP);
    }
}
