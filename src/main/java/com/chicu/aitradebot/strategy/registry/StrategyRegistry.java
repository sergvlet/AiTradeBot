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
 * Универсальный реестр стратегий (v4):
 *  1) UI-метаданные (FieldMeta) — старый модуль, оставлен полностью.
 *  2) Реестр Java-бинов стратегий (register/getStrategy) — ядро v4.
 * StrategyBindingProcessor вызывает register() автоматически.
 */
@Slf4j
@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class StrategyRegistry {

    // =====================================================================
    // 1) МЕТАДАННЫЕ ДЛЯ UI (СТАРЫЙ МЕХАНИЗМ — НЕ УБИРАЕМ)
    // =====================================================================

    @Data
    @AllArgsConstructor
    public static class FieldMeta {
        private String name;     // имя поля в StrategySettings
        private String label;    // label в UI
        private String type;     // text | number | checkbox
    }

    private final Map<StrategyType, List<FieldMeta>> fields = new EnumMap<>(StrategyType.class);

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
    // 2) РЕЕСТР JAVA-СТРАТЕГИЙ (ДЛЯ ENGINE)
    // =====================================================================

    private final Map<StrategyType, TradingStrategy> strategies =
            new EnumMap<>(StrategyType.class);

    /**
     * Вызывается автопроцессором StrategyBindingProcessor.
     */
    public void register(StrategyType type, TradingStrategy strategy) {
        strategies.put(type, strategy);
        log.info("📌 Strategy registered: {} → {}", type, strategy.getClass().getSimpleName());
    }

    /**
     * Получить стратегию (основной метод).
     */
    public TradingStrategy getStrategy(StrategyType type) {
        return strategies.get(type);
    }

    /**
     * Алиас для обратной совместимости.
     * Некоторые сервисы вызывали registry.get(type).
     */
    public TradingStrategy get(StrategyType type) {
        return strategies.get(type);
    }
}
