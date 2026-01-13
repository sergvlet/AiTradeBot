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

import java.util.Optional;

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/strategies")
public class StrategyDashboardController {

    private final WebStrategyFacade webStrategyFacade;
    private final StrategySettingsService strategySettingsService;
    private final MarketDataStreamService marketDataStreamService;

    /**
     * 📊 Strategy dashboard

     * ВАЖНО:
     * - НЕ кидаем 500, если стратегия ещё не настроена
     * - Показываем страницу с понятным состоянием "не настроено"
     */
    @GetMapping("/{type}/dashboard")
    public String strategyDashboardPage(
            @PathVariable StrategyType type,
            @RequestParam Long chatId,
            Model model
    ) {

        // =====================================================
        // 0) БАЗОВЫЙ UI-КОНТЕКСТ (чтобы страница всегда открывалась)
        // =====================================================
        model.addAttribute("page", "strategies/strategy_dashboard");
        model.addAttribute("chatId", chatId);
        model.addAttribute("type", type);

        // =====================================================
        // 1) LOAD STRATEGY SETTINGS (SINGLE SOURCE OF TRUTH)
        // =====================================================
        Optional<StrategySettings> opt =
                strategySettingsService.findLatest(chatId, type, null, null);

        // ✅ НЕТ НАСТРОЕК — НЕ 500, а нормальная страница
        if (opt.isEmpty()) {
            log.warn("⚠️ DASHBOARD: StrategySettings not found (NOT CONFIGURED) chatId={} type={}", chatId, type);

            // флаг для шаблона
            model.addAttribute("configured", false);

            // минимум данных, чтобы шаблон не падал на null
            model.addAttribute("strategy", null);
            model.addAttribute("symbol", null);
            model.addAttribute("exchange", null);
            model.addAttribute("network", null);

            StrategyRunInfo info = new StrategyRunInfo();
            info.setActive(false);
            info.setSymbol(null);
            info.setTimeframe(null);
            info.setExchangeName(null);
            info.setNetworkType(null);

            model.addAttribute("info", info);

            // можно ещё показать красивое сообщение
            model.addAttribute("notice",
                    "Стратегия ещё не настроена. Зайди в «Настройки», выбери символ и таймфрейм, затем открой дашборд.");

            return "layout/app";
        }

        StrategySettings settings = opt.get();

        // =====================================================
        // 2) VALIDATE REQUIRED FIELDS (symbol/timeframe)
        // =====================================================
        String rawSymbol = settings.getSymbol();
        String rawTimeframe = settings.getTimeframe();

        boolean configured = rawSymbol != null && !rawSymbol.isBlank()
                             && rawTimeframe != null && !rawTimeframe.isBlank();

        model.addAttribute("configured", configured);
        model.addAttribute("strategy", settings);

        // если частично пусто — тоже не падаем
        if (!configured) {
            log.warn("⚠️ DASHBOARD: StrategySettings present but incomplete chatId={} type={} id={} symbol={} timeframe={}",
                    chatId, type, settings.getId(), rawSymbol, rawTimeframe);

            model.addAttribute("symbol", rawSymbol);
            model.addAttribute("exchange", settings.getExchangeName());
            model.addAttribute("network", settings.getNetworkType() != null ? settings.getNetworkType().name() : null);

            StrategyRunInfo info = new StrategyRunInfo();
            info.setActive(Boolean.TRUE.equals(settings.isActive()));
            info.setSymbol(rawSymbol);
            info.setTimeframe(rawTimeframe);
            info.setExchangeName(settings.getExchangeName());
            info.setNetworkType(settings.getNetworkType());

            model.addAttribute("info", info);

            model.addAttribute("notice",
                    "Настройки стратегии неполные. Укажи символ и таймфрейм в «Настройки», затем вернись на дашборд.");

            return "layout/app";
        }

        // нормализуем уже после проверки
        String symbol = rawSymbol.trim().toUpperCase();
        String timeframe = rawTimeframe.trim().toLowerCase();

        log.info(
                "📊 DASHBOARD SETTINGS id={} chatId={} type={} symbol={} tf={} limit={} ex={} net={} active={}",
                settings.getId(),
                chatId,
                type,
                symbol,
                timeframe,
                settings.getCachedCandlesLimit(),
                settings.getExchangeName(),
                settings.getNetworkType(),
                settings.isActive()
        );

        model.addAttribute("symbol", symbol);
        model.addAttribute("exchange", settings.getExchangeName());
        model.addAttribute("network", settings.getNetworkType() != null ? settings.getNetworkType().name() : null);

        // =====================================================
        // 3) START MARKET STREAM (IDEMPOTENT)
        // =====================================================
        try {
            marketDataStreamService.subscribeCandles(chatId, type, symbol, timeframe);

            log.info("📡 MARKET STREAM OK chatId={} type={} {} {}", chatId, type, symbol, timeframe);

        } catch (Exception e) {
            // НЕ валим страницу
            log.error("❌ MARKET STREAM FAILED chatId={} type={} {} {}", chatId, type, symbol, timeframe, e);
            model.addAttribute("notice",
                    "Не удалось подключить поток рынка (WS). Страница открыта, но данные могут не обновляться.");
        }

        // =====================================================
        // 4) STRATEGY LIVE STATE (RUN INFO)
        // =====================================================
        StrategyRunInfo info =
                webStrategyFacade.getRunInfo(
                        chatId,
                        type,
                        settings.getExchangeName(),
                        settings.getNetworkType()
                );

        if (info == null) {
            log.warn("⚠️ StrategyRunInfo is null chatId={} type={} ex={} net={}",
                    chatId, type, settings.getExchangeName(), settings.getNetworkType());

            info = new StrategyRunInfo();
            info.setActive(false);
            info.setSymbol(symbol);
            info.setTimeframe(timeframe);
            info.setExchangeName(settings.getExchangeName());
            info.setNetworkType(settings.getNetworkType());
        } else {
            // на всякий случай подстрахуем поля, чтобы UI был консистентным
            if (info.getSymbol() == null) info.setSymbol(symbol);
            if (info.getTimeframe() == null) info.setTimeframe(timeframe);
            if (info.getExchangeName() == null) info.setExchangeName(settings.getExchangeName());
            if (info.getNetworkType() == null) info.setNetworkType(settings.getNetworkType());
        }

        model.addAttribute("info", info);

        return "layout/app";
    }
}
