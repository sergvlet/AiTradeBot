package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.web.facade.StrategyUi;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebStrategyFacadeImpl implements WebStrategyFacade {

    private final AiStrategyOrchestrator orchestrator;
    private final StrategySettingsService settingsService;

    // ================================================================
    // 📋 LIST — /strategies
    // ================================================================
    @Override
    public List<StrategyUi> getStrategies(Long chatId, String exchange, NetworkType network) {

        log.info("📋 getStrategies chatId={} exchange={} network={}", chatId, exchange, network);

        // 🔄 получаем стратегии только по chatId и exchange (без фильтра по сети!)
        List<StrategySettings> settingsList = settingsService.findAllByChatId(chatId, exchange);

        // type → latest strategy
        Map<StrategyType, StrategySettings> settingsByType = new HashMap<>();
        for (StrategySettings s : settingsList) {
            settingsByType.putIfAbsent(s.getType(), s);
        }

        List<StrategyUi> result = new ArrayList<>();

        for (StrategyType type : StrategyType.values()) {
            StrategySettings settings = settingsByType.get(type);

            if (settings == null) {
                result.add(StrategyUi.empty(chatId, type, exchange, network));
                continue;
            }

            // actual status (runtime)
            StrategyRunInfo runtime = orchestrator.getStatus(
                    chatId,
                    type,
                    settings.getExchangeName(),
                    settings.getNetworkType()
            );

            boolean active = runtime != null && runtime.isActive();

            StrategyUi baseUi = StrategyUi.fromSettings(List.of(settings)).get(0);

            StrategyUi finalUi = new StrategyUi(
                    baseUi.id(),
                    baseUi.chatId(),
                    baseUi.type(),
                    baseUi.exchangeName(),
                    baseUi.networkType(),
                    active, // override
                    baseUi.symbol(),
                    baseUi.timeframe(),
                    baseUi.takeProfitPct(),
                    baseUi.stopLossPct(),
                    baseUi.commissionPct(),
                    baseUi.riskPerTradePct(),
                    baseUi.title(),
                    baseUi.description(),
                    baseUi.totalProfitPct(),
                    baseUi.mlConfidence(),
                    baseUi.advancedControlMode()
            );

            result.add(finalUi);
        }

        return result;
    }


    // ================================================================
    // 🔁 TOGGLE
    // ================================================================
    @Override
    public StrategyRunInfo toggle(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {

        StrategySettings settings =
                settingsService
                        .findLatest(chatId, type, exchange, network)
                        .orElseGet(() ->
                                settingsService.getOrCreate(
                                        chatId, type, exchange, network
                                )
                        );

        StrategyRunInfo runtime =
                orchestrator.getStatus(
                        chatId,
                        type,
                        settings.getExchangeName(),
                        settings.getNetworkType()
                );

        boolean isRunning = runtime != null && runtime.isActive();

        log.info(
                "🔁 TOGGLE chatId={} type={} running={} symbol={} tf={}",
                chatId,
                type,
                isRunning,
                settings.getSymbol(),
                settings.getTimeframe()
        );

        StrategyRunInfo result = isRunning
                ? orchestrator.stopStrategy(
                chatId,
                type,
                settings.getExchangeName(),
                settings.getNetworkType()
        )
                : orchestrator.startStrategy(
                chatId,
                type,
                settings.getExchangeName(),
                settings.getNetworkType()
        );

        // ⚠️ НЕ источник истины, но полезно для UI/кеша
        settings.setActive(!isRunning);
        settingsService.save(settings);

        return result;
    }

    // ================================================================
    // ℹ DASHBOARD STATUS
    // ================================================================
    @Override
    public StrategyRunInfo getRunInfo(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {

        StrategySettings s =
                settingsService
                        .findLatest(chatId, type, exchange, network)
                        .orElse(null);

        if (s == null) {
            return null;
        }

        StrategyRunInfo runtime =
                orchestrator.getStatus(
                        chatId,
                        type,
                        s.getExchangeName(),
                        s.getNetworkType()
                );

        if (runtime == null) {
            return null;
        }

        runtime.setSymbol(s.getSymbol());
        runtime.setTimeframe(s.getTimeframe());
        runtime.setTakeProfitPct(s.getTakeProfitPct());
        runtime.setStopLossPct(s.getStopLossPct());
        runtime.setCommissionPct(s.getCommissionPct());
        runtime.setRiskPerTradePct(s.getRiskPerTradePct());

        return runtime;
    }

    // ================================================================
    // 🧰 HELPERS
    // ================================================================
    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String nz(String v, String def) {
        return (v != null && !v.isBlank()) ? v : def;
    }

    private static String title(StrategyType type) {
        return switch (type) {
            case SCALPING -> "Scalping";
            case RSI_EMA -> "RSI + EMA";
            case FIBONACCI_GRID -> "Fibonacci Grid";
            case ML_INVEST -> "ML Invest";
            case SMART_FUSION -> "Smart Fusion";
            default -> type.name();
        };
    }

    private static String description(StrategyType type) {
        return switch (type) {
            case SCALPING -> "Быстрые сделки на малых движениях цены";
            case RSI_EMA -> "Трендовая стратегия на RSI и EMA";
            case FIBONACCI_GRID -> "Сетка ордеров по уровням Фибоначчи";
            case ML_INVEST -> "Инвестиционная стратегия с машинным обучением";
            case SMART_FUSION -> "Комбинированная AI-стратегия";
            default -> "Стратегия без описания";
        };
    }
}
