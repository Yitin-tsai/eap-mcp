package com.eap.mcp.tools.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.eap.mcp.simulation.SimulationRequest;
import com.eap.mcp.simulation.SimulationResult;
import com.eap.mcp.simulation.SimulationService;
import com.eap.mcp.simulation.EnhancedSimulationRequest;
import com.eap.mcp.simulation.EnhancedSimulationResult;
import com.eap.mcp.simulation.EnhancedSimulationService;

/**
 * MCP 工具：模擬服務
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SimulationMcpTool {

    private final SimulationService simulationService;
    private final EnhancedSimulationService enhancedSimulationService;

    @Tool(name = "runSimulation", description = "執行簡單交易模擬；若 executeReal=true 則會呼叫實際下單")
    public SimulationResult runSimulation(
            @ToolParam(description = "JSON 格式的模擬請求 (SimulationRequest)", required = true) SimulationRequest req
    ) {
        log.info("runSimulation: {}", req);
        return simulationService.runSimulation(req);
    }

    @Tool(name = "runEnhancedSimulation",
          description = "執行增強版多商家市場模擬。" +
                        "模擬多個商家在不同天氣、區域條件下的電力交易行為。" +
                        "每個商家有：庫存、每小時消耗量、生產能力（受天氣影響）。" +
                        "支援5種決策策略：保守型、平衡型、積極型、趨勢跟隨型、套利型。" +
                        "參數：merchantCount=商家數量, steps=模擬小時數, weatherEnabled=天氣影響, " +
                        "executeReal=是否真實下單, noiseLevel=隨機噪音程度(0-1)")
    public EnhancedSimulationResult runEnhancedSimulation(
            @ToolParam(description = "增強版模擬請求參數", required = true) EnhancedSimulationRequest req
    ) {
        log.info("runEnhancedSimulation: merchantCount={}, steps={}, weather={}",
                req.getMerchantCount(), req.getSteps(), req.isWeatherEnabled());
        return enhancedSimulationService.runSimulation(req);
    }

    @Tool(name = "exportReport", description = "匯出最近一次模擬的報表")
    public Object exportReport(
            @ToolParam(description = "報表類型: simple=簡單模擬, enhanced=增強版模擬", required = false) String type
    ) {
        if ("enhanced".equalsIgnoreCase(type)) {
            EnhancedSimulationResult enhanced = enhancedSimulationService.getLastResult();
            if (enhanced != null) {
                return enhanced;
            }
            EnhancedSimulationResult empty = new EnhancedSimulationResult();
            empty.getEvents().add("尚未執行增強版模擬");
            return empty;
        }

        // 預設返回簡單模擬結果
        SimulationResult last = simulationService.getLastSimulationResult();
        if (last != null) {
            return last;
        }
        SimulationResult r = new SimulationResult();
        r.setSteps(0);
        r.setSymbol("N/A");
        r.getEvents().add("尚未執行模擬");
        return r;
    }

    @Tool(name = "getSimulationStrategies",
          description = "獲取所有可用的商家決策策略說明")
    public String getSimulationStrategies() {
        return """
            可用的商家決策策略：

            1. CONSERVATIVE (保守型)
               - 只在庫存極端情況下交易
               - 庫存 < 8小時: 緊急買入
               - 庫存 > 目標200%: 出清賣出
               - 適合：風險厭惡的商家

            2. BALANCED (平衡型) [預設]
               - 維持目標庫存水位
               - 庫存低於目標: 買入補貨
               - 庫存高於目標: 賣出多餘
               - 考慮價格合理性
               - 適合：大多數商家

            3. AGGRESSIVE (積極型)
               - 頻繁交易，賺取價差
               - 價格低時積極買入
               - 價格高時積極賣出
               - 適合：追求利潤最大化的商家

            4. TREND_FOLLOWER (趨勢跟隨型)
               - 分析價格趨勢
               - 上漲時買入（預期繼續漲）
               - 下跌時賣出（預期繼續跌）
               - 適合：技術分析導向的商家

            5. ARBITRAGE (套利/做市商型)
               - 同時掛買賣單
               - 賺取 bid-ask 價差
               - 提供市場流動性
               - 適合：專業交易商

            天氣對發電的影響：
            - SUNNY (晴天): 太陽能 100%, 風能 30%
            - PARTLY_CLOUDY (多雲): 太陽能 70%, 風能 50%
            - CLOUDY (陰天): 太陽能 30%, 風能 60%
            - RAINY (雨天): 太陽能 10%, 風能 70%
            - WINDY (大風): 太陽能 60%, 風能 100%
            - STORMY (暴風雨): 太陽能 0%, 風能 20%

            區域修正係數：
            - NORTH (北部): 太陽能 0.8x, 風能 1.2x, 消耗 1.1x
            - CENTRAL (中部): 太陽能 1.0x, 風能 0.9x, 消耗 1.0x
            - SOUTH (南部): 太陽能 1.2x, 風能 0.8x, 消耗 0.95x
            - EAST (東部): 太陽能 0.9x, 風能 1.1x, 消耗 0.9x
            - OFFSHORE (離島): 太陽能 0.7x, 風能 1.5x, 消耗 0.8x
            """;
    }
}
