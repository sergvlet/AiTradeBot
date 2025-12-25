package com.chicu.aitradebot.web.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import com.chicu.aitradebot.web.facade.StrategyUi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * StrategyService (v4)
 *
 * Лёгкий сервис для Web-слоя.
 * ❌ Никаких StrategyRegistry
 * ❌ Никаких TradingStrategy
 * ✅ Только WebStrategyFacade (v4)
 */
@Service
@RequiredArgsConstructor
public class StrategyService {

    private final WebStrategyFacade webStrategyFacade;

    // =============================================================
    // 🌍 DEFAULT CONTEXT (временно)
    // =============================================================
    private static final String DEFAULT_EXCHANGE = "BINANCE";
    private static final NetworkType DEFAULT_NETWORK = NetworkType.MAINNET;

    /**
     * Список стратегий для UI.
     */
    public List<StrategyUi> getStrategies(Long chatId) {
        return webStrategyFacade.getStrategies(
                chatId,
                DEFAULT_EXCHANGE,
                DEFAULT_NETWORK
        );
    }

    /**
     * Запуск стратегии.
     */
    public void start(Long chatId, StrategyType type) {
        webStrategyFacade.start(
                chatId,
                type,
                DEFAULT_EXCHANGE,
                DEFAULT_NETWORK
        );
    }

    /**
     * Остановка стратегии.
     */
    public void stop(Long chatId, StrategyType type) {
        webStrategyFacade.stop(
                chatId,
                type,
                DEFAULT_EXCHANGE,
                DEFAULT_NETWORK
        );
    }

    /**
     * Переключение ON / OFF.
     */
    public void toggle(Long chatId, StrategyType type) {
        webStrategyFacade.toggle(
                chatId,
                type,
                DEFAULT_EXCHANGE,
                DEFAULT_NETWORK
        );
    }
}
