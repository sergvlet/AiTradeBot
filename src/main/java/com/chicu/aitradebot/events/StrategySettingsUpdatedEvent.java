package com.chicu.aitradebot.events;

import com.chicu.aitradebot.common.enums.StrategyType;

public record StrategySettingsUpdatedEvent(long chatId, StrategyType type, String source) {
}
