package com.chicu.aitradebot.strategy.registry;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Универсальный реестр стратегий (v4)
 */
@Slf4j
@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class StrategyRegistry {

    // =====================================================================
    // 1) UI-МЕТАДАННЫЕ (НЕ ТРОГАЕМ)
    // =====================================================================

    @Data
    @AllArgsConstructor
    public static class FieldMeta {
        private String name;
        private String label;
        private String type;
    }

    private final Map<StrategyType, List<FieldMeta>> fields =
            new EnumMap<>(StrategyType.class);

    public StrategyRegistry() {

        fields.put(StrategyType.SMART_FUSION, List.of(
                new FieldMeta("emaPeriod", "EMA период", "number"),
                new FieldMeta("atrPeriod", "ATR период", "number"),
                new FieldMeta("tpPct", "Take Profit (%)", "number"),
                new FieldMeta("slPct", "Stop Loss (%)", "number")
        ));

        fields.put(StrategyType.SCALPING, List.of(
                new FieldMeta("windowSize", "Окно анализа", "number"),
                new FieldMeta("priceChangeThreshold", "Порог движения (%)", "number"),
                new FieldMeta("spreadThreshold", "Макс спред (%)", "number"),
                new FieldMeta("orderVolume", "Объём ордера", "number")
        ));

        fields.put(StrategyType.FIBONACCI_GRID, List.of(
                new FieldMeta("gridLevels", "Количество уровней", "number"),
                new FieldMeta("distancePct", "Шаг сетки (%)", "number"),
                new FieldMeta("takeProfitPct", "TP (%)", "number"),
                new FieldMeta("stopLossPct", "SL (%)", "number")
        ));

        fields.put(StrategyType.ML_INVEST, List.of(
                new FieldMeta("confidenceThreshold", "Порог уверенности ML", "number"),
                new FieldMeta("lookback", "Lookback", "number"),
                new FieldMeta("modelName", "Название модели", "text")
        ));
    }

    public List<FieldMeta> getFields(StrategyType type) {
        return fields.getOrDefault(type, List.of());
    }

    // =====================================================================
    // 2) JAVA-РЕЕСТР СТРАТЕГИЙ (ENGINE)
    // =====================================================================

    private final Map<StrategyType, TradingStrategy> strategies =
            new EnumMap<>(StrategyType.class);

    /**
     * Вызывается StrategyBindingProcessor
     */
    public synchronized void register(StrategyType type, TradingStrategy strategy) {

        TradingStrategy prev = strategies.put(type, strategy);

        if (prev != null) {
            log.warn(
                    "⚠ Strategy overwritten: {} | {} → {}",
                    type,
                    prev.getClass().getSimpleName(),
                    strategy.getClass().getSimpleName()
            );
        } else {
            log.info(
                    "📌 Strategy registered: {} → {}",
                    type,
                    strategy.getClass().getSimpleName()
            );
        }
    }

    /**
     * Основной метод
     */
    public TradingStrategy getStrategy(StrategyType type) {
        TradingStrategy strategy = strategies.get(type);

        if (strategy == null) {
            log.error("❌ Strategy NOT FOUND for type={}", type);
        }

        return strategy;
    }

    /**
     * Алиас (используется в StrategyMarketBridge)
     */
    public TradingStrategy get(StrategyType type) {
        return getStrategy(type);
    }
}
