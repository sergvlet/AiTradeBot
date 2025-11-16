package com.chicu.aitradebot.web.service;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.UserStrategy;
import com.chicu.aitradebot.repository.UserStrategyRepository;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import com.chicu.aitradebot.web.model.StrategyViewModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Сервис для отображения и управления стратегиями (UI/Web).
 * Работает как с зарегистрированными стратегиями (через StrategyRegistry),
 * так и с пользовательскими стратегиями (через UserStrategyRepository).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyService {

    private final StrategyRegistry strategyRegistry;
    private final ApplicationContext context;
    private final UserStrategyRepository userStrategyRepository;

    /**
     * Возвращает все зарегистрированные стратегии (глобальные, из кода).
     * Для них формируется URL вида /strategies/{type}/settings
     */
    public List<StrategyViewModel> getAllView() {
        Map<StrategyType, Class<? extends TradingStrategy>> registered = strategyRegistry.getAll();

        return registered.entrySet().stream()
                .map(entry -> {
                    StrategyType type = entry.getKey();
                    Class<? extends TradingStrategy> clazz = entry.getValue();
                    TradingStrategy strategyBean = getBeanSafely(clazz);

                    boolean active = strategyBean != null && strategyBean.isActive();

                    return StrategyViewModel.builder()
                            .id(null) // глобальные стратегии не имеют ID в БД
                            .strategyType(type.name())
                            .strategyName(type.name().replace("_", " "))
                            .active(active)
                            .totalProfitPct("—")
                            .mlConfidence("—")
                            .settingsUrl("/strategies/" + type.name().toLowerCase() + "/settings") // ✅ по типу
                            .detailsUrl("/strategies/" + type.name().toLowerCase())
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * Возвращает стратегии конкретного пользователя по chatId.
     */
    public List<StrategyViewModel> getUserStrategies(Long chatId) {
        return userStrategyRepository.findByUserChatId(chatId)
                .stream()
                .map(this::toViewModel)
                .collect(Collectors.toList());
    }

    /**
     * Возвращает стратегию по ID (из базы).
     */
    public StrategyViewModel getByIdView(Long id) {
        return userStrategyRepository.findById(id)
                .map(this::toViewModel)
                .orElseThrow(() -> new IllegalArgumentException("❌ Strategy not found: " + id));
    }

    /**
     * Возвращает стратегию по типу (из кода, без ID).
     */
    public StrategyViewModel getByTypeView(StrategyType type) {
        Class<? extends TradingStrategy> clazz = strategyRegistry.getAll().get(type);
        if (clazz == null)
            throw new IllegalArgumentException("❌ Unknown strategy type: " + type);

        TradingStrategy strategyBean = getBeanSafely(clazz);
        boolean active = strategyBean != null && strategyBean.isActive();

        return StrategyViewModel.builder()
                .strategyType(type.name())
                .strategyName(type.name().replace("_", " "))
                .active(active)
                .totalProfitPct("—")
                .mlConfidence("—")
                .build();
    }

    /**
     * Преобразует сущность UserStrategy в ViewModel для отображения.
     */
    private StrategyViewModel toViewModel(UserStrategy entity) {
        return StrategyViewModel.builder()
                .id(entity.getId())
                .strategyType(entity.getStrategySettings().getStrategyType().name())
                .strategyName(entity.getStrategySettings().getStrategyName())
                .active(entity.isActive())
                .totalProfitPct(entity.getTotalProfitPct() + " %")
                .mlConfidence(entity.getMlConfidence().toString())
                .settingsUrl("/strategies/" + entity.getId() + "/config") // ✅ по ID из БД
                .detailsUrl("/strategies/" +
                        entity.getStrategySettings().getStrategyType().name().toLowerCase())
                .build();
    }

    /**
     * Переключает активность стратегии по имени (из реестра).
     */
    public void toggleByName(String name) {
        try {
            StrategyType type = StrategyType.valueOf(name.toUpperCase());
            toggle(type);
        } catch (IllegalArgumentException e) {
            log.warn("❌ Неизвестная стратегия: {}", name);
        }
    }

    private void toggle(StrategyType type) {
        Class<? extends TradingStrategy> clazz = strategyRegistry.getAll().get(type);
        if (clazz == null) {
            log.warn("⚠️ Стратегия {} не найдена в реестре", type);
            return;
        }

        TradingStrategy strategy = getBeanSafely(clazz);
        if (strategy == null) {
            log.warn("⚠️ Не удалось получить бин для {}", clazz.getSimpleName());
            return;
        }

        if (strategy.isActive()) {
            strategy.stop();
            log.info("🛑 Остановлена стратегия {}", type);
        } else {
            strategy.start();
            log.info("🚀 Запущена стратегия {}", type);
        }
    }

    /**
     * Безопасное получение Spring-бина стратегии.
     */
    private TradingStrategy getBeanSafely(Class<? extends TradingStrategy> clazz) {
        try {
            return context.getBean(clazz);
        } catch (Exception e) {
            log.warn("Не удалось получить бин для {}: {}", clazz.getSimpleName(), e.getMessage());
            return null;
        }

    }
    /**
     * Возвращает стратегию по имени (например "SMART_FUSION", "RSI_EMA", "Scalping" и т.д.).
     * Работает для глобальных стратегий, не хранящихся в БД.
     */
    public StrategyViewModel getByName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Имя стратегии не указано");
        }

        // нормализуем: "smart fusion" → "SMART_FUSION"
        String normalized = name.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase();

        try {
            StrategyType type = StrategyType.valueOf(normalized);
            return getByTypeView(type);
        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Не удалось определить StrategyType по имени '{}'", name);
            throw new IllegalArgumentException("Неизвестная стратегия: " + name);
        }
    }

}
