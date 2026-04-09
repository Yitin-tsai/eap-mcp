package com.eap.mcp.simulation.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 天氣狀況 - 影響太陽能/風能發電量
 */
@Getter
@AllArgsConstructor
public enum WeatherCondition {

    SUNNY("晴天", 1.0, 0.3),           // 太陽能100%, 風能30%
    PARTLY_CLOUDY("多雲", 0.7, 0.5),   // 太陽能70%, 風能50%
    CLOUDY("陰天", 0.3, 0.6),          // 太陽能30%, 風能60%
    RAINY("雨天", 0.1, 0.7),           // 太陽能10%, 風能70%
    STORMY("暴風雨", 0.0, 0.2),        // 太陽能0%, 風能20% (太強反而要停機)
    WINDY("大風", 0.6, 1.0);           // 太陽能60%, 風能100%

    private final String description;
    private final double solarFactor;   // 太陽能發電係數 0-1
    private final double windFactor;    // 風能發電係數 0-1

    /**
     * 計算綜合發電係數 (假設太陽能60%, 風能40%的配比)
     */
    public double getCombinedFactor() {
        return solarFactor * 0.6 + windFactor * 0.4;
    }

    /**
     * 隨機獲取天氣，帶有季節性權重
     */
    public static WeatherCondition randomWeather(java.util.Random random, int hourOfDay) {
        // 白天更可能晴天，晚上無太陽能
        double roll = random.nextDouble();

        if (hourOfDay >= 6 && hourOfDay <= 18) {
            // 白天
            if (roll < 0.3) return SUNNY;
            if (roll < 0.5) return PARTLY_CLOUDY;
            if (roll < 0.7) return CLOUDY;
            if (roll < 0.85) return WINDY;
            if (roll < 0.95) return RAINY;
            return STORMY;
        } else {
            // 夜間 - 太陽能無效
            if (roll < 0.3) return CLOUDY;
            if (roll < 0.5) return PARTLY_CLOUDY;
            if (roll < 0.7) return WINDY;
            if (roll < 0.9) return RAINY;
            return STORMY;
        }
    }
}
