package com.chicu.aitradebot.strategy.windowscalping;

public interface WindowScalpingStrategySettingsService {

    WindowScalpingStrategySettings getOrCreate(Long chatId);

    WindowScalpingStrategySettings update(Long chatId, WindowScalpingStrategySettings incoming);
}
