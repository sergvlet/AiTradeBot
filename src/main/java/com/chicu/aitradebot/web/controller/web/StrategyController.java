package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.service.UserProfileService;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import com.chicu.aitradebot.web.view.StrategyConfigView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/strategies")
@RequiredArgsConstructor
@Slf4j
public class StrategyController {

    private final WebStrategyFacade strategyFacade;
    private final UserProfileService userProfileService;

    // ================================================================
    // 📋 СПИСОК СТРАТЕГИЙ
    // ================================================================
    @GetMapping
    public String strategies(Model model,
                             @RequestParam(required = false) Long chatIdParam) {

        Long chatId = (chatIdParam != null)
                ? chatIdParam
                : resolveCurrentChatIdOrThrow();

        model.addAttribute("active", "strategies");
        model.addAttribute("pageTitle", "AI Trading — Стратегии");
        model.addAttribute("strategies", strategyFacade.getStrategies(chatId));
        model.addAttribute("chatId", chatId);

        return "strategies";
    }

    // ================================================================
    // 📊 ДАШБОРД КОНКРЕТНОЙ СТРАТЕГИИ
    // ================================================================
    @GetMapping("/{type}")
    public String strategyDashboard(@PathVariable StrategyType type,
                                    @RequestParam(required = false) Long chatIdParam,
                                    Model model) {

        Long chatId = (chatIdParam != null)
                ? chatIdParam
                : resolveCurrentChatIdOrThrow();

        var strategies = strategyFacade.getStrategies(chatId);
        var uiOpt = strategies.stream()
                .filter(s -> s.strategyType() == type)
                .findFirst();

        if (uiOpt.isEmpty()) {
            log.warn("Стратегия {} не найдена для chatId={}", type, chatId);
            model.addAttribute("pageTitle", "Ошибка");
            model.addAttribute("error", "Стратегия " + type + " не найдена для пользователя.");
            model.addAttribute("active", "strategies");
            return "error";
        }

        var ui = uiOpt.get();

        String symbol = (ui.symbol() != null && !ui.symbol().isBlank())
                ? ui.symbol()
                : "BTCUSDT"; // fallback, но используется крайне редко

        log.info("📊 Открытие дашборда стратегии {} chatId={} symbol={}", type, chatId, symbol);

        model.addAttribute("active", "strategies");
        model.addAttribute("pageTitle", "Стратегия: " + type);
        model.addAttribute("chatId", chatId);
        model.addAttribute("type", type);

        // ⭐ самый важный атрибут → используется JS-графиком
        model.addAttribute("symbol", symbol);
        model.addAttribute("strategySymbol", symbol); // совместимость со старым шаблоном

        model.addAttribute("info", null);
        model.addAttribute("trades", null);

        return "dashboard";
    }


    // ================================================================
    // ⚙️ НАСТРОЙКИ СТРАТЕГИИ (форма конфигурации)
    // ================================================================
    @GetMapping("/{type}/settings")
    public String strategySettings(@PathVariable StrategyType type,
                                   @RequestParam(required = false) Long chatIdParam,
                                   Model model) {

        Long chatId = (chatIdParam != null)
                ? chatIdParam
                : resolveCurrentChatIdOrThrow();

        var strategies = strategyFacade.getStrategies(chatId);
        var uiOpt = strategies.stream()
                .filter(s -> s.strategyType() == type)
                .findFirst();

        if (uiOpt.isEmpty()) {
            log.warn("Стратегия {} не найдена для chatId={} (settings)", type, chatId);
            model.addAttribute("pageTitle", "Ошибка");
            model.addAttribute("error", "Стратегия " + type + " не найдена для пользователя.");
            model.addAttribute("active", "strategies");
            return "error";
        }

        var ui = uiOpt.get();

        // то, что нужно шаблону strategy-config.html: strategy.strategyName, strategy.symbol и т.д.
        StrategyConfigView view = StrategyConfigView.builder()
                .strategyType(type)
                .strategyName(ui.title())
                .description(ui.description())
                .chatId(chatId)
                .symbol(ui.symbol())
                .build();

        model.addAttribute("active", "strategies");
        model.addAttribute("pageTitle", "Настройки — " + type);
        model.addAttribute("strategyType", type);
        model.addAttribute("chatId", chatId);
        model.addAttribute("strategy", view); // <== ВАЖНО для strategy.strategyName в шаблоне

        // дальше сюда можно будет добавить реальные "settings" для конкретного типа стратегии
        return "strategy-config";
    }

    // ================================================================
    // ▶️ ЗАПУСК / ⏹ ОСТАНОВКА / 🔁 TOGGLE
    // ================================================================
    @PostMapping("/start")
    public String startStrategy(@RequestParam Long chatId,
                                @RequestParam StrategyType type) {
        log.info("▶ Запуск стратегии {} для chatId={}", type, chatId);
        strategyFacade.start(chatId, type);
        return "redirect:/strategies?chatId=" + chatId;
    }

    @PostMapping("/stop")
    public String stopStrategy(@RequestParam Long chatId,
                               @RequestParam StrategyType type) {
        log.info("⏹ Остановка стратегии {} для chatId={}", type, chatId);
        strategyFacade.stop(chatId, type);
        return "redirect:/strategies?chatId=" + chatId;
    }

    @PostMapping("/toggle")
    public String toggleStrategy(@RequestParam Long chatId,
                                 @RequestParam StrategyType type) {
        log.info("🔁 Переключение стратегии {} для chatId={}", type, chatId);
        strategyFacade.toggle(chatId, type);
        return "redirect:/strategies?chatId=" + chatId;
    }

    // ================================================================
    // 🧩 HELPERS
    // ================================================================
    private Long resolveCurrentChatIdOrThrow() {
        try {
            Long chatId = userProfileService.getCurrentChatId();
            if (chatId == null || chatId <= 0) {
                throw new IllegalStateException("Не найден активный пользователь (chatId).");
            }
            return chatId;
        } catch (Exception e) {
            log.warn("Не удалось получить текущий chatId: {}", e.getMessage());
            throw new IllegalStateException("Не удалось определить текущего пользователя.", e);
        }
    }
}
