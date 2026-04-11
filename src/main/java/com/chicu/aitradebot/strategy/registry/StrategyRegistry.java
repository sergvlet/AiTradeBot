package com.chicu.aitradebot.strategy.registry;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Универсальный реестр стратегий (v4)
 */
@Slf4j
@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class StrategyRegistry {

    // =====================================================================
    // 1) UI-МЕТАДАННЫЕ
    // =====================================================================

    @Data
    @AllArgsConstructor
    public static class FieldMeta {
        private String name;
        private String label;
        private String type;
    }

    private final Map<StrategyType, List<FieldMeta>> fields = new EnumMap<>(StrategyType.class);

    /**
     * Потокобезопасный runtime-реестр стратегий.
     * Регистрируется через StrategyBindingProcessor.
     */
    private final Map<StrategyType, TradingStrategy> strategies = new ConcurrentHashMap<>();

    public StrategyRegistry() {
        fields.put(StrategyType.SMART_FUSION, List.of(
                new FieldMeta("emaPeriod", "EMA период", "number"),
                new FieldMeta("atrPeriod", "ATR период", "number"),
                new FieldMeta("tpPct", "Take Profit (%)", "number"),
                new FieldMeta("slPct", "Stop Loss (%)", "number")
        ));

        fields.put(StrategyType.SCALPING, List.of(
                new FieldMeta("symbol", "Символ", "text"),
                new FieldMeta("timeframe", "Таймфрейм", "text"),
                new FieldMeta("cachedCandlesLimit", "Свечей в кэше", "number"),
                new FieldMeta("windowSize", "Окно анализа", "number"),
                new FieldMeta("microWindowSize", "Микро-окно", "number"),
                new FieldMeta("orderVolume", "Объём ордера", "number"),

                new FieldMeta("regimeAutoEnabled", "Авто-режим рынка", "checkbox"),
                new FieldMeta("allowTrendTrades", "Разрешить trend", "checkbox"),
                new FieldMeta("allowRangeTrades", "Разрешить range", "checkbox"),
                new FieldMeta("allowBreakoutTrades", "Разрешить breakout", "checkbox"),
                new FieldMeta("allowCounterTrendTrades", "Разрешить counter-trend", "checkbox"),
                new FieldMeta("chaosBlockThreshold", "Порог CHAOS", "number"),
                new FieldMeta("squeezeThreshold", "Порог SQUEEZE", "number"),

                new FieldMeta("trendMinScore", "Trend min score", "number"),
                new FieldMeta("pullbackMaxDepthPct", "Глубина отката (%)", "number"),
                new FieldMeta("pullbackEntryBufferPct", "Буфер входа pullback (%)", "number"),
                new FieldMeta("trendTpPct", "Trend TP (%)", "number"),
                new FieldMeta("trendSlPct", "Trend SL (%)", "number"),
                new FieldMeta("trendBreakEvenPct", "Trend break-even (%)", "number"),
                new FieldMeta("trendMaxHoldSec", "Trend max hold (sec)", "number"),

                new FieldMeta("rangeMinScore", "Range min score", "number"),
                new FieldMeta("rangeEntryFromLowPct", "Вход от низа range (%)", "number"),
                new FieldMeta("rangeExitToMidPct", "Выход к середине range (%)", "number"),
                new FieldMeta("rangeTpPct", "Range TP (%)", "number"),
                new FieldMeta("rangeSlPct", "Range SL (%)", "number"),
                new FieldMeta("rangeMaxHoldSec", "Range max hold (sec)", "number"),

                new FieldMeta("breakoutMinScore", "Breakout min score", "number"),
                new FieldMeta("breakoutVolumeFactor", "Фактор объёма breakout", "number"),
                new FieldMeta("breakoutTpPct", "Breakout TP (%)", "number"),
                new FieldMeta("breakoutSlPct", "Breakout SL (%)", "number"),

                new FieldMeta("maxSpreadPct", "Макс. спред (%)", "number"),
                new FieldMeta("minAtrPct", "Мин. ATR (%)", "number"),
                new FieldMeta("maxAtrPct", "Макс. ATR (%)", "number"),
                new FieldMeta("minVolumeRatio", "Мин. volume ratio", "number"),
                new FieldMeta("minRiskReward", "Мин. risk/reward", "number"),
                new FieldMeta("cooldownAfterStopSec", "Кулдаун после стопа (sec)", "number"),
                new FieldMeta("cooldownAfterExitSec", "Кулдаун после выхода (sec)", "number"),
                new FieldMeta("maxConsecutiveStops", "Макс. стопов подряд", "number"),
                new FieldMeta("reentryLockSec", "Блок повторного входа (sec)", "number"),
                new FieldMeta("emergencyChaosExitEnabled", "Emergency chaos exit", "checkbox"),
                new FieldMeta("partialExitEnabled", "Частичный выход", "checkbox"),
                new FieldMeta("partialExitPct", "Частичный выход доля", "number"),
                new FieldMeta("partialExitTriggerPct", "Триггер частичного выхода (%)", "number"),
                new FieldMeta("useIntrabarConfirmation", "Intrabar confirmation", "checkbox")
        ));

        fields.put(StrategyType.FIBONACCI_GRID, List.of(
                new FieldMeta("gridLevels", "Количество уровней", "number"),
                new FieldMeta("distancePct", "Шаг сетки (%)", "number"),
                new FieldMeta("takeProfitPct", "TP (%)", "number"),
                new FieldMeta("stopLossPct", "SL (%)", "number")
        ));
    }

    public List<FieldMeta> getFields(StrategyType type) {
        if (type == null) {
            return List.of();
        }
        return fields.getOrDefault(type, List.of());
    }

    // =====================================================================
    // 2) JAVA-РЕЕСТР СТРАТЕГИЙ (ENGINE)
    // =====================================================================

    public void register(StrategyType type, TradingStrategy strategy) {
        if (type == null) {
            throw new IllegalArgumentException("StrategyType is null");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("TradingStrategy is null for type=" + type);
        }

        TradingStrategy prev = strategies.put(type, strategy);

        if (prev == null) {
            log.info("📌 Strategy registered: {} → {}", type, strategy.getClass().getSimpleName());
            return;
        }

        if (prev == strategy) {
            log.debug("ℹ Strategy already registered: {} → {}", type, strategy.getClass().getSimpleName());
            return;
        }

        log.warn("⚠ Strategy overwritten: {} | {} → {}",
                type,
                prev.getClass().getSimpleName(),
                strategy.getClass().getSimpleName());
    }

    /**
     * Основной метод (nullable).
     */
    public TradingStrategy getStrategy(StrategyType type) {
        if (type == null) {
            return null;
        }

        TradingStrategy strategy = strategies.get(type);

        if (strategy == null) {
            log.error("❌ Strategy NOT FOUND for type={}. Registered={}", type, strategies.keySet());
        }

        return strategy;
    }

    /**
     * Строгий вариант: если стратегии нет — кидаем понятную ошибку.
     */
    public TradingStrategy require(StrategyType type) {
        TradingStrategy s = getStrategy(type);
        if (s == null) {
            throw new IllegalStateException(
                    "Strategy NOT FOUND for type=" + type + ". Registered=" + strategies.keySet()
            );
        }
        return s;
    }

    /**
     * Алиас.
     */
    public TradingStrategy get(StrategyType type) {
        return getStrategy(type);
    }

    public boolean isRegistered(StrategyType type) {
        return type != null && strategies.containsKey(type);
    }

    public Set<StrategyType> getRegisteredTypes() {
        return Set.copyOf(strategies.keySet());
    }

    public Map<StrategyType, TradingStrategy> snapshot() {
        return Map.copyOf(strategies);
    }
}

