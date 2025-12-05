package com.chicu.aitradebot.strategy.registry;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class StrategyRegistry {

    // ========= СТАРЫЙ UI-ФУНКЦИОНАЛ (оставляем полностью) =========

    @Data
    @AllArgsConstructor
    public static class FieldMeta {
        private String name;   // имя свойства в StrategySettings
        private String label;  // название для UI
        private String type;   // text | number | checkbox
    }

    private final Map<StrategyType, List<FieldMeta>> fields = new HashMap<>();

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

    // ========= НОВАЯ ЧАСТЬ — РЕЕСТР СТРАТЕГИЙ ДЛЯ ENGINE =========

    /** Реестр настоящих Java-объектов стратегий */
    private final Map<StrategyType, TradingStrategy> strategies = new EnumMap<>(StrategyType.class);

    /** StrategyBindingProcessor вызывает это автоматически */
    public void register(StrategyType type, TradingStrategy strategy) {
        strategies.put(type, strategy);
        log.info("📌 Strategy registered: {} → {}", type, strategy.getClass().getSimpleName());
    }

    /** Получить стратегию по типу (для StrategyEngine) */
    public TradingStrategy getStrategy(StrategyType type) {
        return strategies.get(type);
    }
}
