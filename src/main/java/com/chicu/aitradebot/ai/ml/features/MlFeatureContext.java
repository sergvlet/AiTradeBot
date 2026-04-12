package com.chicu.aitradebot.ai.ml.features;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class MlFeatureContext {
    private long chatId;
    private StrategyType strategyType;
    private String symbol;
    private String timeframe;

    /**
     * BUY/SELL — какой сигнал стратегия хочет подтвердить.
     */
    private String action;

    /**
     * modelKey/schemaHash приходят из StrategySettings/реестра артефактов.
     * Gate не должен “угадывать” их.
     */
    private String modelKey;
    private String schemaHash;

    /**
     * Свечи, которые стратегия уже использует.
     */
    private List<MlCandle> candles;

    /**
     * Любые доп. поля стратегии (спред, окно, ATR, уровни и т.п.)
     */
    private Map<String, Object> extra;
}
