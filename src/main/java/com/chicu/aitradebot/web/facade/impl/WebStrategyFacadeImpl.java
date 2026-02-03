package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.web.facade.StrategyUi;
import com.chicu.aitradebot.web.facade.WebStrategyFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    @Transactional(readOnly = true)
    public List<StrategyUi> getStrategies(Long chatId, String exchange, NetworkType network) {

        String exFilter = normExchange(exchange);

        log.info("📋 getStrategies chatId={} exchange={} (norm={}) network={}",
                chatId, exchange, exFilter, network);

        // ✅ Берём все настройки по chatId (+ exchange фильтр)
        List<StrategySettings> all = settingsService.findAllByChatId(chatId, exFilter);

        // Группируем по type и берём “последнюю” (по id)
        Map<StrategyType, StrategySettings> latestByType = new EnumMap<>(StrategyType.class);

        for (StrategySettings s : all) {
            if (s == null || s.getType() == null) continue;

            if (network != null && s.getNetworkType() != network) continue;

            StrategyType type = s.getType();
            StrategySettings cur = latestByType.get(type);

            if (cur == null) {
                latestByType.put(type, s);
                continue;
            }

            Long curId = cur.getId();
            Long newId = s.getId();
            if (newId != null && (curId == null || newId > curId)) {
                latestByType.put(type, s);
            }
        }

        List<StrategyUi> result = new ArrayList<>();

        for (StrategyType type : StrategyType.values()) {

            StrategySettings settings = latestByType.get(type);

            if (settings == null) {
                result.add(StrategyUi.empty(chatId, type, exFilter, network));
                continue;
            }

            // ✅ ВАЖНО: статус берём по реальному exchange/network из settings
            String ex = normExchange(settings.getExchangeName());
            NetworkType net = settings.getNetworkType();

            boolean active = false;
            try {
                StrategyRunInfo runtime = orchestrator.getStatus(chatId, type, ex, net);
                active = runtime != null && runtime.isActive();
            } catch (Exception e) {
                log.warn("⚠ getStatus failed chatId={} type={} ex={} net={} : {}",
                        chatId, type, ex, net, e.getMessage());
            }

            StrategyUi baseUi = StrategyUi.fromSettings(settings);
            result.add(baseUi.withActive(active));
        }

        return result;
    }

    // ================================================================
    // 🔁 TOGGLE
    // ================================================================
    @Override
    @Transactional
    public StrategyRunInfo toggle(Long chatId, StrategyType type, String exchange, NetworkType network) {

        String ex = normExchange(exchange);

        if (chatId == null || chatId <= 0 || type == null || ex == null || network == null) {
            log.warn("⚠ TOGGLE пропуск: chatId={} type={} ex={} net={}", chatId, type, exchange, network);
            StrategyRunInfo info = new StrategyRunInfo();
            info.setActive(false);
            info.setExchangeName(ex);
            info.setNetworkType(network);
            return info;
        }

        // ✅ никаких findLatest(...) — только getSettings/getOrCreate
        StrategySettings settings = settingsService.getSettings(chatId, type, ex, network);
        if (settings == null) {
            settings = settingsService.getOrCreate(chatId, type, ex, network);
        }

        StrategyRunInfo runtime = orchestrator.getStatus(chatId, type, ex, network);
        boolean isRunning = runtime != null && runtime.isActive();

        log.info("🔁 TOGGLE chatId={} type={} running={} ex={} net={} symbol={} tf={}",
                chatId, type, isRunning, ex, network, settings.getSymbol(), settings.getTimeframe());

        return isRunning
                ? orchestrator.stopStrategy(chatId, type, ex, network)
                : orchestrator.startStrategy(chatId, type, ex, network);
    }

    // ================================================================
    // ℹ DASHBOARD STATUS
    // ================================================================
    @Override
    @Transactional(readOnly = true)
    public StrategyRunInfo getRunInfo(Long chatId, StrategyType type, String exchange, NetworkType network) {

        String ex = normExchange(exchange);

        if (chatId == null || chatId <= 0 || type == null || ex == null || network == null) {
            StrategyRunInfo info = new StrategyRunInfo();
            info.setActive(false);
            info.setExchangeName(ex);
            info.setNetworkType(network);
            return info;
        }

        StrategySettings s = settingsService.getSettings(chatId, type, ex, network);

        // ✅ Даже если settings нет — возвращаем “пустой” runtime, чтобы UI не падал
        StrategyRunInfo runtime = orchestrator.getStatus(chatId, type, ex, network);
        if (runtime == null) {
            runtime = new StrategyRunInfo();
            runtime.setActive(false);
            runtime.setExchangeName(ex);
            runtime.setNetworkType(network);
        }

        if (s != null) {
            // ✅ то, что точно живёт в StrategySettings
            runtime.setSymbol(s.getSymbol());
            runtime.setTimeframe(s.getTimeframe());

            // если orchestrator не проставил — подстрахуем
            if (runtime.getExchangeName() == null) runtime.setExchangeName(ex);
            if (runtime.getNetworkType() == null) runtime.setNetworkType(network);
        }

        return runtime;
    }

    // ================================================================
    // helpers
    // ================================================================
    private static String normExchange(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}
