package com.eap.mcp.simulation;

import com.eap.common.dto.MarketMetricsResponse;
import com.eap.mcp.client.WalletServiceClient;
import com.eap.mcp.simulation.model.*;
import com.eap.mcp.simulation.strategy.MerchantStrategy;
import com.eap.mcp.simulation.strategy.StrategyFactory;
import com.eap.mcp.tools.mcp.MarketMetricsMcpTool;
import com.eap.mcp.tools.mcp.TradingMcpTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 增強版模擬服務
 * 模擬多商家在不同天氣、區域條件下的電力交易行為
 *
 * 重要：此模擬使用真實的市場數據（從訂單簿獲取），
 * 模擬中的交易會影響真實市場，外部用戶的交易也會影響模擬結果。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnhancedSimulationService {

    private final TradingMcpTool tradingTool;
    private final MarketMetricsMcpTool marketMetricsTool;
    private final WalletServiceClient walletServiceClient;

    private volatile EnhancedSimulationResult lastResult;

    // 價格歷史 (用於趨勢分析)
    private final List<BigDecimal> priceHistory = new ArrayList<>();
    private static final int MAX_PRICE_HISTORY = 24;

    // 預設價格 (當無法獲取市場數據時使用)
    private static final BigDecimal DEFAULT_BID = BigDecimal.valueOf(95);
    private static final BigDecimal DEFAULT_ASK = BigDecimal.valueOf(105);

    public EnhancedSimulationResult runSimulation(EnhancedSimulationRequest request) {
        log.info("開始增強版模擬: {} 個商家, {} 步驟, 使用真實市場數據",
                request.getMerchantCount(), request.getSteps());

        EnhancedSimulationResult result = new EnhancedSimulationResult();
        result.setSymbol(request.getSymbol());
        result.setTotalSteps(request.getSteps());
        result.setStartTime(LocalDateTime.now());

        // 初始化隨機數生成器
        Random random = request.getRandomSeed() == 0
                ? new Random()
                : new Random(request.getRandomSeed());

        // 初始化商家
        List<MerchantAgent> merchants = initializeMerchants(request, random);
        result.setMerchantCount(merchants.size());

        // 記錄每個商家的初始狀態（用於計算獲利）
        for (MerchantAgent merchant : merchants) {
            merchant.recordInitialState();
        }

        // 清空價格歷史
        priceHistory.clear();

        // 統計變數
        BigDecimal highestPrice = BigDecimal.ZERO;
        BigDecimal lowestPrice = BigDecimal.valueOf(Double.MAX_VALUE);
        BigDecimal priceSum = BigDecimal.ZERO;
        int totalTrades = 0;
        int totalBuys = 0;
        int totalSells = 0;
        double totalVolume = 0;
        BigDecimal spreadSum = BigDecimal.ZERO;
        int validPriceSteps = 0;

        // 執行模擬
        for (int step = 0; step < request.getSteps(); step++) {
            int hourOfDay = step % 24;

            // 決定天氣
            WeatherCondition weather = request.isWeatherEnabled()
                    ? WeatherCondition.randomWeather(random, hourOfDay)
                    : WeatherCondition.PARTLY_CLOUDY;

            // ========== 從真實市場獲取價格 ==========
            MarketPrice marketPrice = fetchRealMarketPrice();
            BigDecimal currentBid = marketPrice.bid;
            BigDecimal currentAsk = marketPrice.ask;

            EnhancedSimulationResult.StepResult stepResult = new EnhancedSimulationResult.StepResult();
            stepResult.setStep(step);
            stepResult.setHourOfDay(hourOfDay);
            stepResult.setWeather(weather);
            stepResult.setBestBid(currentBid);
            stepResult.setBestAsk(currentAsk);
            stepResult.setSpread(currentAsk.subtract(currentBid));
            stepResult.setMidPrice(currentBid.add(currentAsk).divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP));

            String priceSource = marketPrice.fromRealMarket ? "真實市場" : "預設價格";
            result.addEvent(String.format("時間: %02d:00, 天氣: %s, Bid: %.2f, Ask: %.2f (%s)",
                    hourOfDay, weather.getDescription(),
                    currentBid.doubleValue(), currentAsk.doubleValue(), priceSource));

            double stepProduction = 0;
            double stepConsumption = 0;

            // 每個商家做決策
            List<TradeDecision> decisions = new ArrayList<>();
            for (MerchantAgent merchant : merchants) {
                // 更新生產和消耗
                Region region = Region.fromString(merchant.getRegion());
                double production = region.calculateProduction(merchant.getProductionCapacity(), weather);
                double consumption = region.calculateConsumption(merchant.getHourlyConsumption(), hourOfDay);

                // 加入噪音
                production *= (1 + (random.nextDouble() - 0.5) * request.getNoiseLevel());
                consumption *= (1 + (random.nextDouble() - 0.5) * request.getNoiseLevel());

                merchant.updateInventory(production);
                stepProduction += production;
                stepConsumption += consumption;

                // 獲取策略並做決策（使用真實市場價格）
                MerchantStrategy strategy = StrategyFactory.getStrategy(merchant.getStrategyType());
                BigDecimal[] historyArray = priceHistory.toArray(new BigDecimal[0]);

                TradeDecision decision = strategy.decide(
                        merchant, currentBid, currentAsk, historyArray, random);

                decisions.add(decision);

                // 記錄交易
                EnhancedSimulationResult.TradeRecord record = new EnhancedSimulationResult.TradeRecord();
                record.setMerchantId(merchant.getUserId());
                record.setMerchantName(merchant.getName());
                record.setAction(decision.getAction().name());
                record.setPrice(decision.getPrice());
                record.setQuantity(decision.getQuantity());
                record.setReason(decision.getReason());
                record.setConfidence(decision.getConfidence());

                // 執行交易
                if (decision.getAction() != TradeDecision.Action.HOLD) {
                    if (request.isExecuteReal() && merchant.getUserId() != null) {
                        // ========== 真實下單到市場 ==========
                        try {
                            executeRealTrade(merchant, decision, record, step);
                            result.addEvent(String.format("商家 %s 真實下單: %s %.2f x %d",
                                    merchant.getName(), decision.getAction(),
                                    decision.getPrice().doubleValue(), decision.getQuantity()));
                        } catch (Exception e) {
                            record.setExecuted(false);
                            record.setExecutionResult("執行失敗: " + e.getMessage());
                            log.warn("商家 {} 下單失敗: {}", merchant.getName(), e.getMessage());
                        }
                    } else {
                        // 模擬執行（只更新商家內部庫存，不影響真實市場）
                        simulateTrade(merchant, decision, step);
                        record.setExecuted(false);
                        record.setExecutionResult("模擬執行（未影響真實市場）");
                    }

                    totalTrades++;
                    if (decision.getAction() == TradeDecision.Action.BUY) {
                        totalBuys++;
                        totalVolume += decision.getQuantity();
                    } else if (decision.getAction() == TradeDecision.Action.SELL) {
                        totalSells++;
                        totalVolume += decision.getQuantity();
                    }
                }

                stepResult.getTrades().add(record);
            }

            stepResult.setTotalProduction(stepProduction);
            stepResult.setTotalConsumption(stepConsumption);
            result.getStepResults().add(stepResult);

            // 記錄價格歷史（使用真實價格）
            BigDecimal midPrice = currentBid.add(currentAsk).divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP);
            priceHistory.add(midPrice);
            if (priceHistory.size() > MAX_PRICE_HISTORY) {
                priceHistory.remove(0);
            }

            // 更新統計
            if (marketPrice.fromRealMarket) {
                if (currentAsk.compareTo(highestPrice) > 0) highestPrice = currentAsk;
                if (currentBid.compareTo(lowestPrice) < 0) lowestPrice = currentBid;
                priceSum = priceSum.add(midPrice);
                spreadSum = spreadSum.add(currentAsk.subtract(currentBid));
                validPriceSteps++;
            }

            // 如果是真實執行模式，在步驟間稍作延遲讓市場有時間處理
            if (request.isExecuteReal() && step < request.getSteps() - 1) {
                try {
                    Thread.sleep(100); // 100ms 延遲
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // 計算最終統計
        result.setEndTime(LocalDateTime.now());

        // 市場統計 (先計算，因為後面需要用到平均價格)
        EnhancedSimulationResult.MarketStatistics stats = result.getMarketStatistics();
        if (validPriceSteps > 0) {
            stats.setHighestPrice(highestPrice);
            stats.setLowestPrice(lowestPrice);
            stats.setAveragePrice(priceSum.divide(BigDecimal.valueOf(validPriceSteps), RoundingMode.HALF_UP));
            stats.setAverageSpread(spreadSum.divide(BigDecimal.valueOf(validPriceSteps), RoundingMode.HALF_UP));
        } else {
            stats.setHighestPrice(DEFAULT_ASK);
            stats.setLowestPrice(DEFAULT_BID);
            stats.setAveragePrice(DEFAULT_BID.add(DEFAULT_ASK).divide(BigDecimal.valueOf(2)));
            stats.setAverageSpread(DEFAULT_ASK.subtract(DEFAULT_BID));
        }
        stats.setTotalTrades(totalTrades);
        stats.setTotalBuyOrders(totalBuys);
        stats.setTotalSellOrders(totalSells);
        stats.setTotalVolume(totalVolume);

        // 獲取平均價格用於計算庫存價值
        BigDecimal averagePrice = stats.getAveragePrice();

        // 商家最終狀態 - 填充所有追蹤欄位
        for (MerchantAgent merchant : merchants) {
            EnhancedSimulationResult.MerchantFinalState state = new EnhancedSimulationResult.MerchantFinalState();

            // 基本資訊
            state.setUserId(merchant.getUserId());
            state.setName(merchant.getName());
            state.setStrategy(merchant.getStrategyType().name());
            state.setRegion(merchant.getRegion());
            state.setRealUser(merchant.isRealUser());

            // 庫存變化
            state.setInitialInventory(merchant.getInitialInventory());
            state.setFinalInventory(merchant.getInventory());
            state.setInventoryChange(merchant.getInventory() - merchant.getInitialInventory());

            // 生產與消耗統計
            state.setTotalProduction(merchant.getTotalProduction());
            state.setTotalConsumption(merchant.getTotalConsumption());

            // 交易次數
            state.setTotalBuys(merchant.getBuyCount());
            state.setTotalSells(merchant.getSellCount());
            state.setTotalHolds(merchant.getHoldCount());

            // 交易金額
            state.setTotalBuyValue(merchant.getTotalBuyValue());
            state.setTotalSellValue(merchant.getTotalSellValue());
            state.setAverageBuyPrice(merchant.getAverageBuyPrice());
            state.setAverageSellPrice(merchant.getAverageSellPrice());

            // 財務狀況
            state.setInitialBalance(merchant.getInitialBalance());
            state.setFinalBalance(merchant.getBalance());
            state.setBalanceChange(merchant.getBalance().subtract(merchant.getInitialBalance()));

            // 獲利計算
            state.setNetProfit(merchant.getNetProfit());
            state.setInventoryValueChange(merchant.getInventoryValueChange(averagePrice));
            state.setTotalAssetChange(merchant.getTotalAssetChange(averagePrice));

            // 風險與績效指標
            state.setRiskTolerance(merchant.getRiskTolerance());
            state.setTargetInventoryDays(merchant.getTargetInventoryDays());

            result.getMerchantFinalStates().put(merchant.getUserId(), state);

            // 記錄商家結算日誌
            log.info("商家 {} ({}) 結算: 資產變化=${}, 庫存變化={} kWh, 買入{}次/賣出{}次",
                    merchant.getName(), merchant.getStrategyType(),
                    state.getTotalAssetChange(), state.getInventoryChange(),
                    state.getTotalBuys(), state.getTotalSells());
        }

        this.lastResult = result;
        log.info("模擬完成: {} 筆交易, 平均價格 {}, 使用真實市場數據步驟: {}/{}",
                totalTrades, stats.getAveragePrice(), validPriceSteps, request.getSteps());

        return result;
    }

    /**
     * 從真實市場獲取當前價格
     */
    private MarketPrice fetchRealMarketPrice() {
        try {
            MarketMetricsResponse metrics = marketMetricsTool.getMarketMetrics();

            if (metrics != null && metrics.isSuccess() && metrics.getOrderBook() != null) {
                var orderBook = metrics.getOrderBook();
                BigDecimal bid = DEFAULT_BID;
                BigDecimal ask = DEFAULT_ASK;
                boolean hasRealData = false;

                // 獲取最佳買價
                if (orderBook.getBids() != null && !orderBook.getBids().isEmpty()
                        && orderBook.getBids().get(0) != null
                        && orderBook.getBids().get(0).getPrice() != null) {
                    bid = BigDecimal.valueOf(orderBook.getBids().get(0).getPrice());
                    hasRealData = true;
                }

                // 獲取最佳賣價
                if (orderBook.getAsks() != null && !orderBook.getAsks().isEmpty()
                        && orderBook.getAsks().get(0) != null
                        && orderBook.getAsks().get(0).getPrice() != null) {
                    ask = BigDecimal.valueOf(orderBook.getAsks().get(0).getPrice());
                    hasRealData = true;
                }

                // 確保 ask > bid
                if (ask.compareTo(bid) <= 0) {
                    ask = bid.add(BigDecimal.ONE);
                }

                if (hasRealData) {
                    log.debug("獲取真實市場價格: Bid={}, Ask={}", bid, ask);
                    return new MarketPrice(bid, ask, true);
                }
            }
        } catch (Exception e) {
            log.warn("無法獲取真實市場數據，使用預設價格: {}", e.getMessage());
        }

        // 返回預設價格
        return new MarketPrice(DEFAULT_BID, DEFAULT_ASK, false);
    }

    /**
     * 市場價格封裝
     */
    private static class MarketPrice {
        final BigDecimal bid;
        final BigDecimal ask;
        final boolean fromRealMarket;

        MarketPrice(BigDecimal bid, BigDecimal ask, boolean fromRealMarket) {
            this.bid = bid;
            this.ask = ask;
            this.fromRealMarket = fromRealMarket;
        }
    }

    private List<MerchantAgent> initializeMerchants(EnhancedSimulationRequest request, Random random) {
        List<MerchantAgent> merchants = new ArrayList<>();

        // ========== 當 executeReal=true 時，查詢真實已註冊用戶 ==========
        List<UUID> realUserIds = new ArrayList<>();
        if (request.isExecuteReal()) {
            try {
                realUserIds = walletServiceClient.listUsers(request.getMerchantCount());
                log.info("查詢到 {} 位已註冊用戶用於真實交易模擬", realUserIds.size());

                if (realUserIds.isEmpty()) {
                    log.warn("未找到已註冊用戶，無法執行真實交易模擬！請先註冊用戶。");
                } else if (realUserIds.size() < request.getMerchantCount()) {
                    log.warn("已註冊用戶數量({})不足，請求商家數量為{}，將只使用可用用戶",
                            realUserIds.size(), request.getMerchantCount());
                }
            } catch (Exception e) {
                log.error("查詢已註冊用戶失敗: {}", e.getMessage());
                realUserIds = new ArrayList<>();
            }
        }

        // 如果有自訂配置（可以是真實用戶）
        if (request.getMerchants() != null && !request.getMerchants().isEmpty()) {
            for (EnhancedSimulationRequest.MerchantConfig config : request.getMerchants()) {
                // 如果提供了 userId，視為真實用戶
                boolean isRealUser = config.getUserId() != null && !config.getUserId().isEmpty();
                merchants.add(MerchantAgent.builder()
                        .userId(config.getUserId() != null ? config.getUserId() : UUID.randomUUID().toString())
                        .name(config.getName() != null ? config.getName() : "商家" + merchants.size())
                        .region(config.getRegion())
                        .isRealUser(isRealUser)
                        .inventory(config.getInventory())
                        .hourlyConsumption(config.getHourlyConsumption())
                        .productionCapacity(config.getProductionCapacity())
                        .strategyType(config.getStrategy())
                        .riskTolerance(config.getRiskTolerance())
                        .targetInventoryDays(config.getTargetInventoryDays())
                        .balance(BigDecimal.valueOf(10000))
                        .build());
            }
        }

        // 自動生成商家
        Region[] regions = Region.values();
        int realUserIndex = 0;

        while (merchants.size() < request.getMerchantCount()) {
            int index = merchants.size();
            Region region = regions[random.nextInt(regions.length)];

            // 隨機策略 (加權分配)
            MerchantStrategyType strategy = getWeightedRandomStrategy(random);

            // ========== 當 executeReal=true 且有可用真實用戶時，使用真實用戶 ID ==========
            String userId;
            boolean isRealUser;
            if (request.isExecuteReal() && realUserIndex < realUserIds.size()) {
                userId = realUserIds.get(realUserIndex).toString();
                isRealUser = true;
                realUserIndex++;
                log.info("商家{} 使用真實用戶 ID: {}", index + 1, userId);
            } else {
                userId = UUID.randomUUID().toString();
                isRealUser = false;
            }

            merchants.add(MerchantAgent.builder()
                    .userId(userId)
                    .name("商家" + (index + 1))
                    .region(region.name())
                    .isRealUser(isRealUser)
                    .inventory(50 + random.nextDouble() * 150)  // 50-200 初始庫存
                    .hourlyConsumption(5 + random.nextDouble() * 15) // 5-20 每小時消耗
                    .productionCapacity(3 + random.nextDouble() * 12) // 3-15 生產能力
                    .strategyType(strategy)
                    .riskTolerance(0.3 + random.nextDouble() * 0.5) // 0.3-0.8 風險偏好
                    .targetInventoryDays(1 + random.nextDouble() * 3) // 1-4 天庫存目標
                    .balance(BigDecimal.valueOf(10000))
                    .build());
        }

        // 記錄最終商家配置
        long realUserCount = merchants.stream().filter(MerchantAgent::isRealUser).count();
        log.info("商家初始化完成: 總共 {} 位商家，其中 {} 位使用真實用戶 ID",
                merchants.size(), realUserCount);

        return merchants;
    }

    private MerchantStrategyType getWeightedRandomStrategy(Random random) {
        double roll = random.nextDouble();
        if (roll < 0.30) return MerchantStrategyType.CONSERVATIVE;
        if (roll < 0.65) return MerchantStrategyType.BALANCED;
        if (roll < 0.85) return MerchantStrategyType.AGGRESSIVE;
        if (roll < 0.95) return MerchantStrategyType.TREND_FOLLOWER;
        return MerchantStrategyType.ARBITRAGE;
    }

    private void simulateTrade(MerchantAgent merchant, TradeDecision decision, int step) {
        // 使用增強版交易記錄方法，追蹤完整交易歷史
        merchant.executeTrade(step, decision, true);
    }

    private void executeRealTrade(MerchantAgent merchant, TradeDecision decision,
                                   EnhancedSimulationResult.TradeRecord record, int step) {
        boolean executed = false;

        if (decision.getAction() == TradeDecision.Action.BUY) {
            var response = tradingTool.placeOrder(
                    merchant.getUserId(),
                    "BUY",
                    decision.getPrice().toPlainString(),
                    String.valueOf(decision.getQuantity()),
                    "ELC"
            );
            record.setExecuted(response.isSuccess());
            record.setExecutionResult(response.getMessage());
            executed = response.isSuccess();

        } else if (decision.getAction() == TradeDecision.Action.SELL) {
            var response = tradingTool.placeOrder(
                    merchant.getUserId(),
                    "SELL",
                    decision.getPrice().toPlainString(),
                    String.valueOf(decision.getQuantity()),
                    "ELC"
            );
            record.setExecuted(response.isSuccess());
            record.setExecutionResult(response.getMessage());
            executed = response.isSuccess();

        } else if (decision.getAction() == TradeDecision.Action.BUY_SELL) {
            // 做市商策略：同時下買賣單
            boolean buyExecuted = false;
            boolean sellExecuted = false;

            if (decision.getBidPrice() != null && decision.getBidQuantity() > 0) {
                var buyResponse = tradingTool.placeOrder(
                        merchant.getUserId(),
                        "BUY",
                        decision.getBidPrice().toPlainString(),
                        String.valueOf(decision.getBidQuantity()),
                        "ELC"
                );
                buyExecuted = buyResponse.isSuccess();
                record.setExecutionResult("買單: " + buyResponse.getMessage());
            }

            if (decision.getAskPrice() != null && decision.getAskQuantity() > 0) {
                var sellResponse = tradingTool.placeOrder(
                        merchant.getUserId(),
                        "SELL",
                        decision.getAskPrice().toPlainString(),
                        String.valueOf(decision.getAskQuantity()),
                        "ELC"
                );
                sellExecuted = sellResponse.isSuccess();
                String existingResult = record.getExecutionResult();
                record.setExecutionResult(existingResult + " | 賣單: " + sellResponse.getMessage());
            }

            executed = buyExecuted || sellExecuted;
            record.setExecuted(executed);
        }

        // 使用增強版交易記錄方法
        merchant.executeTrade(step, decision, executed);
    }

    public EnhancedSimulationResult getLastResult() {
        return lastResult;
    }
}
