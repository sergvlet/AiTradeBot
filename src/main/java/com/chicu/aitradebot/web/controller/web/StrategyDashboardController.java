package com.chicu.aitradebot.web.controller.web;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
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

    private final WebStrategyFacade webStrategyFacade;
    private final StrategySettingsService strategySettingsService;
    private final MarketDataStreamService marketDataStreamService;

    @GetMapping("/{type}/dashboard")
    public String strategyDashboardPage(
            @PathVariable StrategyType type,
            @RequestParam Long chatId,
            Model model
    ) {
        // =====================================================
        // 0) БАЗОВЫЙ UI-КОНТЕКСТ
        // =====================================================
        model.addAttribute("page", "strategies/strategy_dashboard");
        model.addAttribute("chatId", chatId);
        model.addAttribute("type", type);

        // =====================================================
        // 1) LOAD STRATEGY SETTINGS (baseline)
        // =====================================================
        StrategySettings settings = resolveBaselineSettings(chatId, type);

        if (settings == null) {
            log.warn("⚠️ DASHBOARD: StrategySettings not found (NOT CONFIGURED) chatId={} type={}", chatId, type);

            model.addAttribute("configured", false);

            model.addAttribute("strategy", null);
            model.addAttribute("symbol", null);
            model.addAttribute("exchange", null);
            model.addAttribute("network", null);

            StrategyRunInfo info = new StrategyRunInfo();
            info.setActive(false);

            model.addAttribute("info", info);
            model.addAttribute("notice",
                    "Стратегия ещё не настроена. Зайди в «Настройки», выбери символ и таймфрейм, затем открой дашборд.");

            return "layout/app";
        }

        // =====================================================
        // 2) VALIDATE REQUIRED FIELDS
        // =====================================================
        String rawSymbol = settings.getSymbol();
        String rawTimeframe = settings.getTimeframe();

        // Нормализуем аккуратно (но null не трогаем)
        String symbol = (rawSymbol == null) ? null : rawSymbol.trim().toUpperCase(Locale.ROOT);
        String timeframe = (rawTimeframe == null) ? null : rawTimeframe.trim().toLowerCase(Locale.ROOT);

        // exchange/network для потоков/ключей тоже должны быть консистентны
        String exchangeNorm = (settings.getExchangeName() == null) ? null : settings.getExchangeName().trim().toUpperCase(Locale.ROOT);

        boolean configuredBase =
                symbol != null && !symbol.isBlank() &&
                timeframe != null && !timeframe.isBlank();

        boolean configuredMarket =
                configuredBase &&
                exchangeNorm != null && !exchangeNorm.isBlank() &&
                settings.getNetworkType() != null;

        model.addAttribute("configured", configuredMarket); // ✅ “сконфигурировано” = можно реально стримить/рисовать
        model.addAttribute("strategy", settings);

        // для UI — отдаём НОРМАЛИЗОВАННЫЕ значения, чтобы JS не ловил кашу
        model.addAttribute("symbol", symbol);
        model.addAttribute("exchange", exchangeNorm);
        model.addAttribute("network", settings.getNetworkType() != null ? settings.getNetworkType().name() : null);

        // если частично пусто — не падаем
        if (!configuredBase) {
            log.warn("⚠️ DASHBOARD: StrategySettings present but incomplete chatId={} type={} id={} symbol={} timeframe={}",
                    chatId, type, settings.getId(), rawSymbol, rawTimeframe);

            StrategyRunInfo info = new StrategyRunInfo();
            info.setActive(settings.isActive());
            info.setSymbol(symbol);
            info.setTimeframe(timeframe);
            info.setExchangeName(exchangeNorm);
            info.setNetworkType(settings.getNetworkType());

            model.addAttribute("info", info);
            model.addAttribute("notice",
                    "Настройки стратегии неполные. Укажи символ и таймфрейм в «Настройки», затем вернись на дашборд.");

            return "layout/app";
        }

        // Если символ/ТФ есть, но биржа/сеть не выбраны — тоже покажем подсказку
        if (!configuredMarket) {
            log.warn("⚠️ DASHBOARD: Settings ok, but exchange/network missing chatId={} type={} id={} ex={} net={}",
                    chatId, type, settings.getId(), settings.getExchangeName(), settings.getNetworkType());

            model.addAttribute("notice",
                    "Не выбрана биржа/сеть. Укажи их в «Настройки», иначе поток рынка и график не подключатся.");
        }

        log.info(
                "📊 DASHBOARD SETTINGS id={} chatId={} type={} symbol={} tf={} limit={} ex={} net={} active={}",
                settings.getId(),
                chatId,
                type,
                symbol,
                timeframe,
                settings.getCachedCandlesLimit(),
                exchangeNorm,
                settings.getNetworkType(),
                settings.isActive()
        );

        // =====================================================
        // 3) START MARKET STREAM (IDEMPOTENT)
        // =====================================================
        if (configuredMarket) {
            try {
                marketDataStreamService.subscribe(
                        exchangeNorm,
                        settings.getNetworkType(),
                        chatId,
                        type,
                        symbol,
                        timeframe
                );
                log.info("📡 MARKET STREAM OK chatId={} type={} ex={} net={} {} {}",
                        chatId, type, exchangeNorm, settings.getNetworkType(), symbol, timeframe);

            } catch (Exception e) {
                log.error("❌ MARKET STREAM FAILED chatId={} type={} ex={} net={} {} {}",
                        chatId, type, exchangeNorm, settings.getNetworkType(), symbol, timeframe, e);

                model.addAttribute("notice",
                        "Не удалось подключить поток рынка (WS). Страница открыта, но данные могут не обновляться.");
            }
        }

        // =====================================================
        // 4) STRATEGY LIVE STATE (RUN INFO)
        // ✅ Тут тоже передаём нормализованный exchange
        // =====================================================
        StrategyRunInfo info =
                webStrategyFacade.getRunInfo(
                        chatId,
                        type,
                        exchangeNorm,
                        settings.getNetworkType()
                );

        if (info == null) {
            log.warn("⚠️ StrategyRunInfo is null chatId={} type={} ex={} net={}",
                    chatId, type, exchangeNorm, settings.getNetworkType());

            info = new StrategyRunInfo();
            info.setActive(false);
        }

        // Подстраховка полей для UI
        if (info.getSymbol() == null) info.setSymbol(symbol);
        if (info.getTimeframe() == null) info.setTimeframe(timeframe);
        if (info.getExchangeName() == null) info.setExchangeName(exchangeNorm);
        if (info.getNetworkType() == null) info.setNetworkType(settings.getNetworkType());

        model.addAttribute("info", info);

        return "layout/app";
    }

    /**
     * baseline selection:
     * - active=true first
     * - then updatedAt desc
     * - then id desc
     */
    private StrategySettings resolveBaselineSettings(Long chatId, StrategyType type) {
        if (chatId == null || chatId <= 0) return null;

        List<StrategySettings> all = strategySettingsService.findAllByChatId(chatId, null);
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
}
