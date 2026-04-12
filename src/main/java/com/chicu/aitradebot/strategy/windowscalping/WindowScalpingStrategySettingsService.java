package com.chicu.aitradebot.strategy.windowscalping;

import com.chicu.aitradebot.common.enums.NetworkType;

public interface WindowScalpingStrategySettingsService {

    WindowScalpingStrategySettings getOrCreate(Long chatId);

    WindowScalpingStrategySettings getOrCreate(Long chatId,
                                               String exchangeName,
                                               NetworkType networkType,
                                               String symbol,
                                               String timeframe);

    WindowScalpingStrategySettings update(Long chatId, WindowScalpingStrategySettings incoming);

    WindowScalpingStrategySettings update(Long chatId,
                                          String exchangeName,
                                          NetworkType networkType,
                                          String symbol,
                                          String timeframe,
                                          WindowScalpingStrategySettings incoming);

    Long getVersion(Long chatId);

    Long getVersion(Long chatId,
                    String exchangeName,
                    NetworkType networkType,
                    String symbol,
                    String timeframe);
}