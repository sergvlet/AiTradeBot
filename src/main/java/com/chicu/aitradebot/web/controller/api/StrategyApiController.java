package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * StrategyControlApiController (v4)
 *
 * Лёгкий контроллер управления стратегиями.
 * Никакого StrategyFacade, никаких прямых вызовов стратегий —
 * только WebStrategyFacade.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/strategy/control")
public class StrategyApiController {

    private final WebStrategyFacade webStrategyFacade;

    /**
     * Список стратегий и их состояние.
     */
    @GetMapping("/list")
    public Object list(@RequestParam Long chatId) {
        return webStrategyFacade.getStrategies(chatId);
    }

    /**
     * Запустить стратегию.
     */
    @PostMapping("/start")
    public void start(
            @RequestParam Long chatId,
            @RequestParam StrategyType type
    ) {
        webStrategyFacade.start(chatId, type);
    }

    /**
     * Остановить стратегию.
     */
    @PostMapping("/stop")
    public void stop(
            @RequestParam Long chatId,
            @RequestParam StrategyType type
    ) {
        webStrategyFacade.stop(chatId, type);
    }

    /**
     * Переключить ON/OFF.
     */
    @PostMapping("/toggle")
    public void toggle(
            @RequestParam Long chatId,
            @RequestParam StrategyType type
    ) {
        webStrategyFacade.toggle(chatId, type);
    }
}
