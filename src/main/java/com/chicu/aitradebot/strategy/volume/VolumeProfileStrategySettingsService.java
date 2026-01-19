// src/main/java/com/chicu/aitradebot/strategy/volume/VolumeProfileStrategySettingsService.java
package com.chicu.aitradebot.strategy.volume;

public interface VolumeProfileStrategySettingsService {

    VolumeProfileStrategySettings getOrCreate(Long chatId);

    VolumeProfileStrategySettings update(Long chatId, VolumeProfileStrategySettings incoming);
}
