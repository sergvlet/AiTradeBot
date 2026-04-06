package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/strategies")
public class StrategyDashboardController {

    private static final String DEFAULT_EXCHANGE = "BINANCE";
    private static final NetworkType DEFAULT_NETWORK = NetworkType.MAINNET;
    private static final String DEFAULT_TIMEFRAME = "1m";

    private final WebStrategyFacade webStrategyFacade;
    private final StrategySettingsService strategySettingsService;

    @GetMapping("/{type}/dashboard")
    public String strategyDashboardPage(
            @PathVariable StrategyType type,
            @RequestParam Long chatId,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String network,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String timeframe,
            Model model
    ) {
        model.addAttribute("page", "strategies/strategy_dashboard");
        model.addAttribute("chatId", chatId);
        model.addAttribute("type", type);

        StrategySettings settings = resolveBaselineSettings(chatId, type);

        if (settings == null) {
            log.warn("⚠️ DASHBOARD: StrategySettings not found (NOT CONFIGURED) chatId={} type={}", chatId, type);

            String exchangeUi = normalizeExchangeOrDefault(exchange);
            NetworkType networkUi = parseNetworkOrDefault(network);
            String symbolUi = normalizeSymbolOrNull(symbol);
            String timeframeUi = normalizeTimeframeOrDefault(timeframe);
            String journalPnlAsset = extractQuoteAsset(symbolUi);

            model.addAttribute("configured", false);
            model.addAttribute("strategy", null);
            model.addAttribute("symbol", symbolUi);
            model.addAttribute("exchange", exchangeUi);
            model.addAttribute("network", networkUi.name());
            model.addAttribute("timeframe", timeframeUi);
            model.addAttribute("journalPnlAsset", journalPnlAsset);

            StrategyRunInfo info = new StrategyRunInfo();
            info.setActive(false);
            info.setExchangeName(exchangeUi);
            info.setNetworkType(networkUi);
            info.setSymbol(symbolUi);
            info.setTimeframe(timeframeUi);

            model.addAttribute("info", info);
            model.addAttribute(
                    "notice",
                    "Стратегия ещё не настроена. Зайди в «Настройки», выбери символ, таймфрейм, биржу и сеть, затем открой дашборд."
            );

            return "layout/app";
        }

        String symbolFromSettings = normalizeSymbolOrNull(settings.getSymbol());
        String timeframeFromSettings = normalizeTimeframeOrNull(settings.getTimeframe());
        String exchangeFromSettings = normalizeExchangeOrNull(settings.getExchangeName());
        NetworkType networkFromSettings = settings.getNetworkType();

        String symbolUi = firstNonBlankSymbol(
                symbolFromSettings,
                normalizeSymbolOrNull(symbol)
        );

        String timeframeUi = firstNonBlankTimeframe(
                timeframeFromSettings,
                normalizeTimeframeOrNull(timeframe),
                DEFAULT_TIMEFRAME
        );

        String exchangeUi = firstNonBlankExchange(
                exchangeFromSettings,
                normalizeExchangeOrNull(exchange),
                DEFAULT_EXCHANGE
        );

        NetworkType networkUi = firstNonNullNetwork(
                networkFromSettings,
                parseNetworkOrNull(network),
                DEFAULT_NETWORK
        );

        String journalPnlAsset = extractQuoteAsset(symbolUi);

        boolean configuredBase =
                symbolUi != null && !symbolUi.isBlank() &&
                        timeframeUi != null && !timeframeUi.isBlank();

        boolean configuredMarket =
                configuredBase &&
                        exchangeUi != null && !exchangeUi.isBlank() &&
                        networkUi != null;

        model.addAttribute("configured", configuredMarket);
        model.addAttribute("strategy", settings);

        model.addAttribute("symbol", symbolUi);
        model.addAttribute("exchange", exchangeUi);
        model.addAttribute("network", networkUi != null ? networkUi.name() : null);
        model.addAttribute("timeframe", timeframeUi);
        model.addAttribute("journalPnlAsset", journalPnlAsset);

        if (!configuredBase) {
            log.warn("⚠️ DASHBOARD: StrategySettings present but incomplete chatId={} type={} id={} symbol={} timeframe={}",
                    chatId, type, settings.getId(), settings.getSymbol(), settings.getTimeframe());

            StrategyRunInfo info = new StrategyRunInfo();
            info.setActive(settings.isActive());
            info.setSymbol(symbolUi);
            info.setTimeframe(timeframeUi);
            info.setExchangeName(exchangeUi);
            info.setNetworkType(networkUi);

            model.addAttribute("info", info);
            model.addAttribute(
                    "notice",
                    "Настройки стратегии неполные. Укажи символ и таймфрейм в «Настройки», затем вернись на дашборд."
            );

            return "layout/app";
        }

        if (!configuredMarket) {
            log.warn("⚠️ DASHBOARD: Settings ok, but exchange/network missing chatId={} type={} id={} ex={} net={}",
                    chatId, type, settings.getId(), settings.getExchangeName(), settings.getNetworkType());

            model.addAttribute(
                    "notice",
                    "Не выбрана биржа/сеть. Укажи их в «Настройки», иначе поток рынка и график не подключатся."
            );
        }

        StrategyRunInfo info = webStrategyFacade.getRunInfo(chatId, type, exchangeUi, networkUi);

        if (info == null) {
            log.warn("⚠️ StrategyRunInfo is null chatId={} type={} ex={} net={}",
                    chatId, type, exchangeUi, networkUi);

            info = new StrategyRunInfo();
            info.setActive(false);
        }

        if (info.getSymbol() == null) info.setSymbol(symbolUi);
        if (info.getTimeframe() == null) info.setTimeframe(timeframeUi);
        if (info.getExchangeName() == null) info.setExchangeName(exchangeUi);
        if (info.getNetworkType() == null) info.setNetworkType(networkUi);

        boolean runtimeActive = info.isActive();
        model.addAttribute("runtimeActive", runtimeActive);
        model.addAttribute("info", info);

        if (settings.isActive() != runtimeActive) {
            log.warn("⚠️ DASHBOARD ACTIVE MISMATCH chatId={} type={} settingsActive={} runtimeActive={} ex={} net={} symbol={} tf={}",
                    chatId, type, settings.isActive(), runtimeActive, exchangeUi, networkUi, symbolUi, timeframeUi);

            // ВАЖНО: дашборд только отображает состояние и не должен сам менять active-флаг.
            // Иначе при старте/рестарте стратегии можно получить ложный repair, когда runtime ещё
            // не успел зарегистрировать binding или ответить через getRunInfo.
            if (settings.isActive() && !runtimeActive && model.getAttribute("notice") == null) {
                model.addAttribute(
                        "notice",
                        "Рантайм стратегии ещё синхронизируется с сохранёнными настройками. Обнови страницу через пару секунд."
                );
            }
        }

        log.info(
                "📊 DASHBOARD SETTINGS id={} chatId={} type={} symbol={} tf={} limit={} ex={} net={} runtimeActive={} dbActive={} pnlAsset={}",
                settings.getId(),
                chatId,
                type,
                symbolUi,
                timeframeUi,
                settings.getCachedCandlesLimit(),
                exchangeUi,
                networkUi,
                runtimeActive,
                settings.isActive(),
                journalPnlAsset
        );

        return "layout/app";
    }

    private StrategySettings resolveBaselineSettings(Long chatId, StrategyType type) {
        if (chatId == null || chatId <= 0) return null;

        List<StrategySettings> all = strategySettingsService.findAllByChatId(chatId);
        if (all == null || all.isEmpty()) return null;

        Comparator<StrategySettings> cmp =
                Comparator.comparing(StrategySettings::isActive).reversed()
                        .thenComparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.reverseOrder()));

        return all.stream()
                .filter(s -> s != null && s.getType() == type)
                .sorted(cmp)
                .findFirst()
                .orElse(null);
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

    private static String normalizeTimeframeOrDefault(String timeframe) {
        String s = normalizeTimeframeOrNull(timeframe);
        return s != null ? s : DEFAULT_TIMEFRAME;
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

    private static String firstNonBlankExchange(String... values) {
        if (values == null) return null;
        for (String v : values) {
            String n = normalizeExchangeOrNull(v);
            if (n != null) return n;
        }
        return null;
    }

    private static NetworkType firstNonNullNetwork(NetworkType... values) {
        if (values == null) return null;
        for (NetworkType v : values) {
            if (v != null) return v;
        }
        return null;
    }

    private static String extractQuoteAsset(String symbol) {
        String normalized = normalizeSymbolOrNull(symbol);
        if (normalized == null) {
            return null;
        }

        for (String quote : List.of("USDT", "USDC", "FDUSD", "BUSD", "USDP", "DAI", "EUR", "TRY", "BTC", "ETH", "BNB")) {
            if (normalized.endsWith(quote) && normalized.length() > quote.length()) {
                return quote;
            }
        }

        return null;
    }
}
