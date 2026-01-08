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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/strategies")
@RequiredArgsConstructor
@Slf4j
public class StrategyController {

    private final WebStrategyFacade strategyFacade;
    private final UserProfileService userProfileService;

    // ================================================================
    // 🌍 DEFAULT CONTEXT (ТОЛЬКО ДЛЯ UI)
    // ================================================================
    private static final String DEFAULT_EXCHANGE = "BINANCE";
    private static final NetworkType DEFAULT_NETWORK = NetworkType.MAINNET;

    // ================================================================
    // 📋 СПИСОК СТРАТЕГИЙ (UI)
    // ================================================================
    @GetMapping
    public String strategies(
            Model model,
            @RequestParam(required = false) Long chatId,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String network
    ) {

        Long resolvedChatId = (chatId != null && chatId > 0)
                ? chatId
                : resolveCurrentChatIdOrThrow();

        String resolvedExchange = normalizeExchangeOrDefault(exchange);
        NetworkType resolvedNetwork = parseNetworkOrDefault(network);

        log.info("📋 OPEN STRATEGIES chatId={} exchange={} network={}",
                resolvedChatId, resolvedExchange, resolvedNetwork);

        model.addAttribute("active", "strategies");
        model.addAttribute("pageTitle", "Стратегии");
        model.addAttribute("page", "strategies");

        model.addAttribute("strategies",
                strategyFacade.getStrategies(resolvedChatId, resolvedExchange, resolvedNetwork));

        model.addAttribute("chatId", resolvedChatId);

        // ✅ чтобы UI мог прокидывать контекст в формы/кнопки
        model.addAttribute("exchange", resolvedExchange);
        model.addAttribute("network", resolvedNetwork.name());

        return "layout/app";
    }

    // ================================================================
    // 🔁 TOGGLE — ЕДИНСТВЕННАЯ ТОЧКА УПРАВЛЕНИЯ
    // ================================================================
    @PostMapping("/toggle")
    public String toggleStrategy(
            @RequestParam(required = false) Long chatId,
            @RequestParam StrategyType type,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String network,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String timeframe,
            @RequestParam(required = false) Integer limit
    ) {
        Long resolvedChatId = (chatId != null && chatId > 0)
                ? chatId
                : resolveCurrentChatIdOrThrow();

        String resolvedExchange = normalizeExchangeOrDefault(exchange);
        NetworkType resolvedNetwork = parseNetworkOrDefault(network);

        log.info("🔁 TOGGLE FROM UI chatId={} type={} exchange={} network={} symbol={} tf={} limit={}",
                resolvedChatId, type, resolvedExchange, resolvedNetwork, symbol, timeframe, limit);

        // 1) переключаем стратегию
        strategyFacade.toggle(resolvedChatId, type, resolvedExchange, resolvedNetwork);

        // 2) редирект на дашборд стратегии (с контекстом)
        StringBuilder url = new StringBuilder();
        url.append("/strategies/")
                .append(type.name())
                .append("/dashboard")
                .append("?chatId=").append(resolvedChatId)
                .append("&exchange=").append(enc(resolvedExchange))
                .append("&network=").append(enc(resolvedNetwork.name()));

        if (symbol != null && !symbol.isBlank()) {
            url.append("&symbol=").append(enc(symbol.trim().toUpperCase()));
        }
        if (timeframe != null && !timeframe.isBlank()) {
            url.append("&timeframe=").append(enc(timeframe.trim().toLowerCase()));
        }
        if (limit != null && limit >= 10 && limit <= 1500) {
            url.append("&limit=").append(limit);
        }

        return "redirect:" + url;
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

    private static String normalizeExchangeOrDefault(String exchange) {
        if (exchange == null) return DEFAULT_EXCHANGE;
        String s = exchange.trim();
        if (s.isEmpty()) return DEFAULT_EXCHANGE;
        return s.toUpperCase();
    }

    private static NetworkType parseNetworkOrDefault(String network) {
        if (network == null) return DEFAULT_NETWORK;
        String s = network.trim();
        if (s.isEmpty()) return DEFAULT_NETWORK;

        // принимаем любые регистры: mainnet/MainNet/MAINNET
        for (NetworkType nt : NetworkType.values()) {
            if (nt.name().equalsIgnoreCase(s)) {
                return nt;
            }
        }

        log.warn("⚠️ Unknown network='{}', fallback to {}", s, DEFAULT_NETWORK);
        return DEFAULT_NETWORK;
    }

    private static String enc(String s) {
        return URLEncoder.encode(String.valueOf(s), StandardCharsets.UTF_8);
    }
}
