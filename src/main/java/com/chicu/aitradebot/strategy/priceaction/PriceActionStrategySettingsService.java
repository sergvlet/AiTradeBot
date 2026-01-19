package com.chicu.aitradebot.strategy.priceaction;

public interface PriceActionStrategySettingsService {

    PriceActionStrategySettings getOrCreate(Long chatId);

    PriceActionStrategySettings update(Long chatId, PriceActionStrategySettings incoming);
}
