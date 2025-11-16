package com.chicu.aitradebot.web.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.UserProfile;
import com.chicu.aitradebot.repository.UserProfileRepository;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettingsRepository;
import com.chicu.aitradebot.web.model.StrategyViewModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 🌐 Сервис управления стратегиями в веб-интерфейсе.
 * Отвечает за отображение, инициализацию и запуск/остановку стратегий.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyService {

    private final StrategyRegistry strategyRegistry;
    private final ApplicationContext context;
    private final SmartFusionStrategySettingsRepository smartFusionRepo;
    private final UserProfileRepository userProfileRepository;

    /**
     * Возвращает список всех стратегий для отображения на странице /strategies.
     * При первом запуске создаёт дефолт SmartFusion для каждого пользователя без настроек.
     */
    @Transactional
    public List<StrategyViewModel> getAllView() {
        // для всех пользователей, у кого ещё нет SmartFusion, создаём дефолт
        userProfileRepository.findAll().forEach(profile ->
                ensureSmartFusionDefault(profile.getChatId(), profile.getNetworkType())
        );

        Map<StrategyType, Class<? extends TradingStrategy>> registered = strategyRegistry.getAll();

        return registered.entrySet().stream()
                .map(entry -> {
                    StrategyType type = entry.getKey();
                    TradingStrategy bean = getBeanSafely(entry.getValue());
                    boolean active = bean != null && bean.isActive();
                    boolean implemented = isImplemented(type);

                    long chatId = resolveChatId();
                    String symbol = resolveSymbol(type);

                    return StrategyViewModel.builder()
                            .id(null)
                            .chatId(chatId)
                            .strategyType(type.name())
                            .strategyName(type.name().replace("_", " "))
                            .symbol(symbol)
                            .active(active)
                            .totalProfitPct(BigDecimal.ZERO)
                            .mlConfidence(BigDecimal.ZERO)
                            .settingsUrl(implemented
                                    ? "/strategies/" + type.name().toLowerCase() + "/settings"
                                    : "#")
                            .detailsUrl(implemented
                                    ? "/strategies/" + type.name().toLowerCase()
                                    : "#")
                            .build();
                })
                .collect(Collectors.toList());
    }

    /** Проверяет, реализована ли стратегия */
    private boolean isImplemented(StrategyType type) {
        return type == StrategyType.SMART_FUSION;
    }

    /**
     * Создаёт дефолт SmartFusion для конкретного пользователя.
     */
    @Transactional
    protected void ensureSmartFusionDefault(Long chatId, NetworkType networkType) {
        if (chatId == null) return;
        if (smartFusionRepo.existsByChatId(chatId)) return;

        SmartFusionStrategySettings s = SmartFusionStrategySettings.builder()
                .chatId(chatId)
                .symbol("BTCUSDT")
                .exchange("BINANCE")
                .capitalUsd(1000.0)
                .timeframe("1m")
                .networkType(networkType != null ? networkType : NetworkType.TESTNET)
                .build();

        smartFusionRepo.save(s);
        log.info("🆕 SmartFusion: создана дефолтная запись (chatId={}, network={})", chatId, networkType);
    }

    /** Возвращает первый доступный chatId */
    private long resolveChatId() {
        return userProfileRepository.findAll().stream()
                .map(UserProfile::getChatId)
                .filter(id -> id != null && id > 0)
                .findFirst()
                .orElse(123L);
    }

    /** Возвращает символ по умолчанию */
    private String resolveSymbol(StrategyType type) {
        try {
            return smartFusionRepo.findAll().stream()
                    .map(SmartFusionStrategySettings::getSymbol)
                    .filter(sym -> sym != null && !sym.isBlank())
                    .findFirst()
                    .orElse("BTCUSDT");
        } catch (Exception e) {
            log.warn("⚠️ Ошибка resolveSymbol для {}: {}", type, e.getMessage());
            return "BTCUSDT";
        }
    }

    /** Безопасное получение Spring Bean стратегии */
    private TradingStrategy getBeanSafely(Class<? extends TradingStrategy> clazz) {
        try {
            return context.getBean(clazz);
        } catch (Exception e) {
            log.debug("ℹ️ Стратегия {} неактивна (в разработке)", clazz.getSimpleName());
            return null;
        }
    }

    /** Переключение стратегии по имени */
    public void toggleByName(String name) {
        try {
            StrategyType type = StrategyType.valueOf(name.toUpperCase());
            toggle(type);
        } catch (IllegalArgumentException e) {
            log.warn("❌ Неизвестная стратегия: {}", name);
        }
    }

    /** Запуск / остановка стратегии */
    private void toggle(StrategyType type) {
        if (!isImplemented(type)) {
            log.info("🧩 Стратегия {} ещё не реализована", type);
            return;
        }
        Class<? extends TradingStrategy> clazz = strategyRegistry.getAll().get(type);
        TradingStrategy strategy = getBeanSafely(clazz);
        if (strategy == null) return;

        if (strategy.isActive()) {
            strategy.stop();
            log.info("🛑 Остановлена стратегия {}", type);
        } else {
            strategy.start();
            log.info("🚀 Запущена стратегия {}", type);
        }
    }

    /** Возвращает карточку стратегии по ID (заглушка для совместимости) */
    public StrategyViewModel getByIdView(Long id) {
        return getByName("SMART_FUSION");
    }

    /** Возвращает карточку стратегии по имени */
    public StrategyViewModel getByName(String name) {
        String normalized = name.trim().replace('-', '_').replace(' ', '_').toUpperCase();
        StrategyType type = StrategyType.valueOf(normalized);
        TradingStrategy bean = getBeanSafely(strategyRegistry.getAll().get(type));
        boolean active = bean != null && bean.isActive();

        return StrategyViewModel.builder()
                .chatId(resolveChatId())
                .symbol(resolveSymbol(type))
                .strategyType(type.name())
                .strategyName(type.name().replace("_", " "))
                .active(active)
                .totalProfitPct(BigDecimal.ZERO)
                .mlConfidence(BigDecimal.ZERO)
                .settingsUrl("/strategies/" + type.name().toLowerCase() + "/settings")
                .detailsUrl("/strategies/" + type.name().toLowerCase())
                .build();
    }
}
