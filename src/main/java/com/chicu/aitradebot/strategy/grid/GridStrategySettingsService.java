// src/main/java/com/chicu/aitradebot/strategy/grid/GridStrategySettingsService.java
package com.chicu.aitradebot.strategy.grid;

public interface GridStrategySettingsService {

    GridStrategySettings getOrCreate(Long chatId);

    GridStrategySettings update(Long chatId, GridStrategySettings incoming);
}
