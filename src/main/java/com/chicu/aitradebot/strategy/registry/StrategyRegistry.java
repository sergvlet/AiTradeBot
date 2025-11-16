package com.chicu.aitradebot.strategy.registry;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 📘 StrategyRegistry
 * Реестр стратегий.
 * - Регистрирует все TradingStrategy с аннотацией @StrategyBinding
 * - Позволяет получить бин стратегии или создать новый экземпляр
 */
@Component
@Slf4j
public class StrategyRegistry {

    private final ApplicationContext context;
    private final Map<StrategyType, Class<? extends TradingStrategy>> strategyClasses = new EnumMap<>(StrategyType.class);

    public StrategyRegistry(ApplicationContext context) {
        this.context = context;
    }

    @PostConstruct
    public void init() {
        var beans = context.getBeansOfType(TradingStrategy.class);
        log.info("🔍 Найдено {} бинов TradingStrategy: {}", beans.size(), beans.keySet());

        beans.forEach((name, strategy) -> {
            StrategyBinding binding = strategy.getClass().getAnnotation(StrategyBinding.class);
            if (binding != null) {
                strategyClasses.put(binding.value(), strategy.getClass());
                log.info("✅ Зарегистрирована стратегия [{}] → {}", binding.value(), strategy.getClass().getSimpleName());
            } else {
                log.warn("⚠️ Пропущен бин без аннотации @StrategyBinding: {}", strategy.getClass().getSimpleName());
            }
        });

        log.info("📊 StrategyRegistry инициализирован: {} стратегий", strategyClasses.size());
    }

    /** Получить зарегистрированный класс стратегии по типу */
    public Class<? extends TradingStrategy> getStrategyClass(StrategyType type) {
        return strategyClasses.get(type);
    }

    /**
     * Получить бин стратегии из контекста по типу.
     * Если бин отсутствует, создаёт новый экземпляр через Reflection.
     */
    public TradingStrategy getStrategy(StrategyType type) {
        Class<? extends TradingStrategy> clazz = strategyClasses.get(type);
        if (clazz == null) {
            log.warn("❌ Стратегия {} не найдена в реестре", type);
            return null;
        }
        try {
            return context.getBean(clazz);
        } catch (Exception e) {
            log.warn("⚠️ Бин {} не найден в контексте, создаём новый экземпляр", clazz.getSimpleName());
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception ex) {
                log.error("Ошибка создания экземпляра стратегии {}: {}", type, ex.getMessage(), ex);
                throw new IllegalStateException("Не удалось создать экземпляр стратегии: " + type, ex);
            }
        }
    }

    /**
     * Создать новый экземпляр стратегии по типу.
     * Если бин есть — вернуть его, иначе создать вручную.
     */
    public TradingStrategy newInstance(StrategyType type) {
        Class<? extends TradingStrategy> clazz = strategyClasses.get(type);
        if (clazz == null) {
            log.error("❌ Неизвестная стратегия: {}", type);
            return null;
        }

        try {
            // 1️⃣ Пробуем получить как Spring Bean
            return context.getBean(clazz);
        } catch (Exception e) {
            // 2️⃣ Если бина нет — создаём вручную
            try {
                TradingStrategy instance = clazz.getDeclaredConstructor().newInstance();
                log.info("✅ Создан экземпляр стратегии {} через Reflection", type);
                return instance;
            } catch (Exception ex) {
                log.error("Ошибка создания экземпляра стратегии {}: {}", type, ex.getMessage(), ex);
                return null;
            }
        }
    }

    /** Получить все зарегистрированные стратегии (копия карты) */
    public Map<StrategyType, Class<? extends TradingStrategy>> getAll() {
        return Map.copyOf(strategyClasses);
    }
    /**
     * Получить стратегию по строковому имени (например, "SMART_FUSION").
     * Если не найдена — выбрасывает IllegalArgumentException.
     */
    public TradingStrategy getStrategyOrThrow(String typeName) {
        try {
            StrategyType type = StrategyType.valueOf(typeName.toUpperCase());
            return getStrategyOrThrow(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("❌ Неизвестный тип стратегии: " + typeName);
        }
    }

    /**
     * Получить стратегию по типу (безопасно).
     * Если не найдена — выбрасывает исключение.
     */
    public TradingStrategy getStrategyOrThrow(StrategyType type) {
        TradingStrategy strategy = getStrategy(type);
        if (strategy == null) {
            throw new IllegalStateException("❌ Стратегия не найдена в реестре: " + type);
        }
        return strategy;
    }
    public TradingStrategy getStrategy(String typeName) {
        try {
            var type = com.chicu.aitradebot.common.enums.StrategyType.valueOf(typeName);
            return getStrategy(type);
        } catch (Exception e) {
            log.warn("Unknown StrategyType: {}", typeName);
            return null;
        }
    }


}
