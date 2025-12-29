package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.service.UserProfileService;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
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
    // 🌍 DEFAULT CONTEXT (ТОЛЬКО ДЛЯ СПИСКА)
    // ================================================================
    private static final String DEFAULT_EXCHANGE = "BINANCE";
    private static final NetworkType DEFAULT_NETWORK = NetworkType.MAINNET;

    // ================================================================
    // 📋 СПИСОК СТРАТЕГИЙ (UI)
    // ================================================================
    @GetMapping
    public String strategies(
            Model model,
            @RequestParam(required = false) Long chatId
    ) {

        Long resolvedChatId = (chatId != null)
                ? chatId
                : resolveCurrentChatIdOrThrow();

        log.info(
                "📋 OPEN STRATEGIES chatId={} exchange={} network={}",
                resolvedChatId, DEFAULT_EXCHANGE, DEFAULT_NETWORK
        );

        model.addAttribute("active", "strategies");
        model.addAttribute("pageTitle", "Стратегии");
        model.addAttribute("page", "strategies");

        model.addAttribute(
                "strategies",
                strategyFacade.getStrategies(
                        resolvedChatId,
                        DEFAULT_EXCHANGE,
                        DEFAULT_NETWORK
                )
        );

        model.addAttribute("chatId", resolvedChatId);

        // (опционально) чтобы UI мог прокинуть контекст в формы
        model.addAttribute("exchange", DEFAULT_EXCHANGE);
        model.addAttribute("network", DEFAULT_NETWORK.name());

        return "layout/app";
    }

    // ================================================================
    // 🔁 TOGGLE — ЕДИНСТВЕННАЯ ТОЧКА УПРАВЛЕНИЯ
    // ================================================================
    @PostMapping("/toggle")
    public String toggleStrategy(
            @RequestParam Long chatId,
            @RequestParam StrategyType type,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) NetworkType network
    ) {

        String resolvedExchange = (exchange != null && !exchange.isBlank())
                ? exchange
                : DEFAULT_EXCHANGE;

        NetworkType resolvedNetwork = (network != null)
                ? network
                : DEFAULT_NETWORK;

        log.info(
                "🔁 TOGGLE FROM UI chatId={} type={} exchange={} network={}",
                chatId, type, resolvedExchange, resolvedNetwork
        );

        strategyFacade.toggle(
                chatId,
                type,
                resolvedExchange,
                resolvedNetwork
        );

        return "redirect:/strategies?chatId=" + chatId;
    }

    // ================================================================
    // 🎯 HELPERS
    // ================================================================
    private Long resolveCurrentChatIdOrThrow() {
        Long chatId = userProfileService.getCurrentChatId();
        if (chatId == null || chatId <= 0) {
            throw new IllegalStateException("ChatId не найден (пользователь не определён)");
        }
        return chatId;
    }
}
