package com.eap.mcp.simulation.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 區域定義 - 不同區域有不同的天氣模式和電力特性
 */
@Getter
@AllArgsConstructor
public enum Region {

    NORTH("北部", 0.8, 1.2, 1.1),      // 較多雨, 風力佳, 消耗量較高
    CENTRAL("中部", 1.0, 0.9, 1.0),    // 平均
    SOUTH("南部", 1.2, 0.8, 0.95),     // 日照充足, 風力普通
    EAST("東部", 0.9, 1.1, 0.9),       // 多雲, 風力佳, 消耗量較低
    OFFSHORE("離島", 0.7, 1.5, 0.8);   // 日照不穩定, 風力極佳, 消耗量低

    private final String name;
    private final double solarModifier;     // 太陽能效率修正
    private final double windModifier;      // 風能效率修正
    private final double consumptionModifier; // 消耗量修正 (人口/工業密度)

    /**
     * 計算該區域的實際發電量
     */
    public double calculateProduction(double baseCapacity, WeatherCondition weather) {
        double solarProduction = baseCapacity * 0.6 * weather.getSolarFactor() * solarModifier;
        double windProduction = baseCapacity * 0.4 * weather.getWindFactor() * windModifier;
        return solarProduction + windProduction;
    }

    /**
     * 計算該區域的實際消耗量
     */
    public double calculateConsumption(double baseConsumption, int hourOfDay) {
        double hourlyModifier = getHourlyConsumptionModifier(hourOfDay);
        return baseConsumption * consumptionModifier * hourlyModifier;
    }

    /**
     * 每小時消耗量修正 (模擬尖峰/離峰)
     */
    private double getHourlyConsumptionModifier(int hourOfDay) {
        // 尖峰時段: 10-12, 18-21
        // 離峰時段: 0-6
        if (hourOfDay >= 0 && hourOfDay < 6) {
            return 0.6; // 深夜離峰
        } else if (hourOfDay >= 6 && hourOfDay < 10) {
            return 0.9; // 早晨
        } else if (hourOfDay >= 10 && hourOfDay < 12) {
            return 1.2; // 上午尖峰
        } else if (hourOfDay >= 12 && hourOfDay < 14) {
            return 1.0; // 午休
        } else if (hourOfDay >= 14 && hourOfDay < 18) {
            return 1.1; // 下午
        } else if (hourOfDay >= 18 && hourOfDay < 21) {
            return 1.3; // 晚間尖峰
        } else {
            return 0.8; // 夜間
        }
    }

    public static Region fromString(String regionName) {
        for (Region r : values()) {
            if (r.name().equalsIgnoreCase(regionName) || r.getName().equals(regionName)) {
                return r;
            }
        }
        return CENTRAL; // 預設
    }
}
