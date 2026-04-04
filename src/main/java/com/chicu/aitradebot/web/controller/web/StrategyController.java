package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.service.UserProfileService;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Controller
@RequestMapping("/strategies")
@RequiredArgsConstructor
@Slf4j
public class StrategyController {

    private final WebStrategyFacade strategyFacade;
    private final UserProfileService userProfileService;
    private final StrategySettingsService strategySettingsService;

    private static final String DEFAULT_EXCHANGE = "BINANCE";
    private static final NetworkType DEFAULT_NETWORK = NetworkType.MAINNET;

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

        // ВАЖНО:
        // список стратегий не должен фильтроваться по UI-дефолту,
        // иначе карточки показывают не реальный сохранённый контекст стратегии.
        String explicitExchange = normalizeExchangeOrNull(exchange);
        NetworkType explicitNetwork = parseNetworkOrNull(network);

        log.info("📋 OPEN STRATEGIES chatId={} exchangeFilter={} networkFilter={}",
                resolvedChatId, explicitExchange, explicitNetwork);

        model.addAttribute("active", "strategies");
        model.addAttribute("pageTitle", "Стратегии");
        model.addAttribute("page", "strategies");

        model.addAttribute("strategies",
                strategyFacade.getStrategies(resolvedChatId, explicitExchange, explicitNetwork));

        model.addAttribute("chatId", resolvedChatId);

        // Это только UI-контекст страницы, не источник истины для стратегии
        model.addAttribute("exchange", explicitExchange);
        model.addAttribute("network", explicitNetwork != null ? explicitNetwork.name() : null);

        return "layout/app";
    }

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

        StrategySettings settings = null;
        try {
            settings = strategySettingsService.getSettings(resolvedChatId, type);
        } catch (Exception ignored) {
            try {
                settings = strategySettingsService.getOrCreate(resolvedChatId, type);
            } catch (Exception ignored2) {
            }
        }

        // Источник истины = сохранённые настройки стратегии.
        // UI-параметры используем только как fallback.
        String effectiveExchange = firstNonBlankExchange(
                settings != null ? settings.getExchangeName() : null,
                exchange,
                DEFAULT_EXCHANGE
        );

        NetworkType effectiveNetwork = firstNonNullNetwork(
                settings != null ? settings.getNetworkType() : null,
                parseNetworkOrNull(network),
                DEFAULT_NETWORK
        );

        String effectiveSymbol = firstNonBlankSymbol(
                settings != null ? settings.getSymbol() : null,
                symbol
        );

        String effectiveTimeframe = firstNonBlankTimeframe(
                settings != null ? settings.getTimeframe() : null,
                timeframe
        );

        log.info("🔁 TOGGLE FROM UI chatId={} type={} reqEx={} reqNet={} effEx={} effNet={} effSymbol={} effTf={} limit={}",
                resolvedChatId,
                type,
                exchange,
                network,
                effectiveExchange,
                effectiveNetwork,
                effectiveSymbol,
                effectiveTimeframe,
                limit);

        strategyFacade.toggle(resolvedChatId, type, effectiveExchange, effectiveNetwork);

        StringBuilder url = new StringBuilder();
        url.append("/strategies/")
                .append(type.name())
                .append("/dashboard")
                .append("?chatId=").append(resolvedChatId);

        // прокидываем уже эффективный, а не карточный контекст
        url.append("&exchange=").append(enc(effectiveExchange));
        url.append("&network=").append(enc(effectiveNetwork.name()));

        if (effectiveSymbol != null) {
            url.append("&symbol=").append(enc(effectiveSymbol));
        }
        if (effectiveTimeframe != null) {
            url.append("&timeframe=").append(enc(effectiveTimeframe));
        }
        if (limit != null && limit >= 10 && limit <= 1500) {
            url.append("&limit=").append(limit);
        }

        return "redirect:" + url;
    }

    private Long resolveCurrentChatIdOrThrow() {
        Long chatId = userProfileService.getCurrentChatId();
        if (chatId == null || chatId <= 0) {
            throw new IllegalStateException("ChatId не найден (пользователь не определён)");
        }
        return chatId;
    }

    private static String normalizeExchangeOrNull(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normalizeExchangeOrDefault(String exchange) {
        String s = normalizeExchangeOrNull(exchange);
        return s != null ? s : DEFAULT_EXCHANGE;
    }

    private static NetworkType parseNetworkOrNull(String network) {
        if (network == null) return null;
        String s = network.trim();
        if (s.isEmpty()) return null;

        for (NetworkType nt : NetworkType.values()) {
            if (nt.name().equalsIgnoreCase(s)) {
                return nt;
            }
        }
        return null;
    }

    private static NetworkType parseNetworkOrDefault(String network) {
        NetworkType parsed = parseNetworkOrNull(network);
        return parsed != null ? parsed : DEFAULT_NETWORK;
    }

    private static String normalizeSymbolOrNull(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normalizeTimeframeOrNull(String timeframe) {
        if (timeframe == null) return null;
        String s = timeframe.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlankExchange(String... values) {
        if (values == null) return DEFAULT_EXCHANGE;
        for (String v : values) {
            String n = normalizeExchangeOrNull(v);
            if (n != null) return n;
        }
        return DEFAULT_EXCHANGE;
    }

    private static String firstNonBlankSymbol(String... values) {
        if (values == null) return null;
        for (String v : values) {
            String n = normalizeSymbolOrNull(v);
            if (n != null) return n;
        }
        return null;
    }

    private static String firstNonBlankTimeframe(String... values) {
        if (values == null) return null;
        for (String v : values) {
            String n = normalizeTimeframeOrNull(v);
            if (n != null) return n;
        }
        return null;
    }

    private static NetworkType firstNonNullNetwork(NetworkType... values) {
        if (values == null) return DEFAULT_NETWORK;
        for (NetworkType v : values) {
            if (v != null) return v;
        }
        return DEFAULT_NETWORK;
    }

    private static String enc(String s) {
        return URLEncoder.encode(String.valueOf(s), StandardCharsets.UTF_8);
    }
}