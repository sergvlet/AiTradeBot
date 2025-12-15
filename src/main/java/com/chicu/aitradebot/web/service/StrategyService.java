package com.chicu.aitradebot.web.service;

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
 * Никакого StrategyRegistry, никаких стратегий напрямую.
 * Всё управление идёт через WebStrategyFacade.
 */
@Service
@RequiredArgsConstructor
public class StrategyService {

    private final WebStrategyFacade webStrategyFacade;

    /**
     * Список стратегий для UI.
     */
    public List<StrategyUi> getStrategies(Long chatId) {
        return webStrategyFacade.getStrategies(chatId);
    }

    /**
     * Запуск стратегии.
     */
    public void start(Long chatId, StrategyType type) {
        webStrategyFacade.start(chatId, type);
    }

    /**
     * Остановка стратегии.
     */
    public void stop(Long chatId, StrategyType type) {
        webStrategyFacade.stop(chatId, type);
    }

    /**
     * Переключение ON/OFF.
     */
    public void toggle(Long chatId, StrategyType type) {
        webStrategyFacade.toggle(chatId, type);
    }
}
