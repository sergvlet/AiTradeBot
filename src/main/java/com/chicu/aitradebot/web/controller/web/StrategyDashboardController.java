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

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/strategies")
public class StrategyDashboardController {

    private final WebStrategyFacade webStrategyFacade;
    private final StrategySettingsService strategySettingsService;

    // ✅ V4 market stream (ЕДИНСТВЕННЫЙ правильный вход)
    private final MarketDataStreamService marketDataStreamService;

    /**
     * 📊 Strategy dashboard
     */
    @GetMapping("/{type}/dashboard")
    public String strategyDashboardPage(
            @PathVariable StrategyType type,
            @RequestParam Long chatId,
            Model model
    ) {

        // =====================================================
        // 1️⃣ LOAD STRATEGY SETTINGS (SINGLE SOURCE OF TRUTH)
        // =====================================================
        StrategySettings settings =
                strategySettingsService
                        .findLatest(chatId, type, null, null)
                        .orElseThrow(() -> new IllegalStateException(
                                "StrategySettings not found chatId=" + chatId +
                                " type=" + type
                        ));

        // 🔒 нормализация
        String symbol = settings.getSymbol().toUpperCase();
        String timeframe = settings.getTimeframe().toLowerCase();

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

        // =====================================================
        // 🔥 2️⃣ START MARKET STREAM (IDEMPOTENT)
        // =====================================================
        try {
            marketDataStreamService.subscribeCandles(
                    chatId,
                    type,
                    symbol,
                    timeframe
            );

            log.info(
                    "📡 MARKET STREAM OK chatId={} type={} {} {}",
                    chatId,
                    type,
                    symbol,
                    timeframe
            );

        } catch (Exception e) {
            log.error(
                    "❌ MARKET STREAM FAILED chatId={} type={} {} {}",
                    chatId,
                    type,
                    symbol,
                    timeframe,
                    e
            );
        }

        // =====================================================
        // 3️⃣ STRATEGY LIVE STATE
        // =====================================================
        StrategyRunInfo info =
                webStrategyFacade.getRunInfo(
                        chatId,
                        type,
                        settings.getExchangeName(),
                        settings.getNetworkType()
                );

        if (info == null) {
            log.warn(
                    "⚠️ StrategyRunInfo is null chatId={} type={} ex={} net={}",
                    chatId,
                    type,
                    settings.getExchangeName(),
                    settings.getNetworkType()
            );
        }

        // =====================================================
        // 4️⃣ MODEL (UI CONTEXT)
        // =====================================================
        model.addAttribute("page", "strategies/strategy_dashboard");

        model.addAttribute("chatId", chatId);
        model.addAttribute("type", type);

        model.addAttribute("strategy", settings);
        model.addAttribute("symbol", symbol);
        model.addAttribute("exchange", settings.getExchangeName());
        model.addAttribute("network", settings.getNetworkType().name());
        model.addAttribute("info", info);

        return "layout/app";
    }
}
