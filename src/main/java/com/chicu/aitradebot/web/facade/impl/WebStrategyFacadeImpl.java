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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

        // ✅ модель: 1 запись на (chatId, type)
        List<StrategySettings> all = settingsService.findAllByChatId(chatId);

        Map<StrategyType, StrategySettings> byType = new EnumMap<>(StrategyType.class);
        for (StrategySettings s : all) {
            if (s == null || s.getType() == null) continue;

            // если пришёл фильтр сети — оставляем только совпадающую
            if (network != null && s.getNetworkType() != network) continue;

            // если пришёл фильтр биржи — оставляем только совпадающую
            if (exFilter != null) {
                String exFromDb = normExchange(s.getExchangeName());
                if (exFromDb == null || !exFilter.equals(exFromDb)) continue;
            }

            // 1 запись на type, но на всякий случай берём "последнюю" по id
            StrategyType type = s.getType();
            StrategySettings cur = byType.get(type);
            if (cur == null) {
                byType.put(type, s);
                continue;
            }
            Long curId = cur.getId();
            Long newId = s.getId();
            if (newId != null && (curId == null || newId > curId)) {
                byType.put(type, s);
            }
        }

        List<StrategyUi> result = new ArrayList<>();

        for (StrategyType type : StrategyType.values()) {

            StrategySettings settings = byType.get(type);

            if (settings == null) {
                // ✅ если нет settings — отдаём пустую карточку (UI не падает)
                result.add(StrategyUi.empty(chatId, type, exFilter, network));
                continue;
            }

            // ✅ статус берём по фактическому exchange/network из settings
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
// 🔒 locks (чтобы stop/resub/start были атомарны)
// ================================================================
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    private Object lockFor(Long chatId, StrategyType type) {
        return locks.computeIfAbsent(chatId + ":" + type.name(), k -> new Object());
    }

    private static String normUpper(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static boolean eq(String a, String b) {
        return Objects.equals(normUpper(a), normUpper(b));
    }

    /**
     * ✅ Автоматический рестарт стратегии, если она активна и контекст (symbol/tf/ex/net) изменился.
     * Вызывать сразу после сохранения StrategySettings из контроллера /config.
     */
    @Transactional
    public StrategyRunInfo restartIfOutOfSync(Long chatId, StrategyType type) {

        if (chatId == null || chatId <= 0 || type == null) {
            StrategyRunInfo info = new StrategyRunInfo();
            info.setActive(false);
            return info;
        }

        synchronized (lockFor(chatId, type)) {

            StrategySettings s;
            try {
                s = settingsService.getSettings(chatId, type);
            } catch (Exception e) {
                log.warn("⚠ restartIfOutOfSync: settings not found chatId={} type={} : {}", chatId, type, e.getMessage());
                StrategyRunInfo info = new StrategyRunInfo();
                info.setActive(false);
                return info;
            }

            String exNew = normExchange(s.getExchangeName());
            NetworkType netNew = s.getNetworkType();
            String symNew = normUpper(s.getSymbol());
            String tfNew  = normUpper(s.getTimeframe());

            if (exNew == null || netNew == null) {
                log.warn("⚠ restartIfOutOfSync: missing ex/net in settings chatId={} type={} ex={} net={}",
                        chatId, type, s.getExchangeName(), s.getNetworkType());
                StrategyRunInfo info = new StrategyRunInfo();
                info.setActive(false);
                info.setExchangeName(exNew);
                info.setNetworkType(netNew);
                return info;
            }

            StrategyRunInfo runtime;
            try {
                runtime = orchestrator.getStatus(chatId, type, exNew, netNew);
            } catch (Exception e) {
                log.warn("⚠ restartIfOutOfSync: getStatus failed chatId={} type={} ex={} net={} : {}",
                        chatId, type, exNew, netNew, e.getMessage());
                runtime = null;
            }

            // если не активна — ничего не делаем
            if (runtime == null || !runtime.isActive()) {
                return runtime != null ? runtime : new StrategyRunInfo();
            }

            // ⚠️ ВАЖНО: runtime.symbol/tf должны приходить из orchestrator (как минимум symbol).
            // Если сейчас runtime.symbol/tf не заполняются — ниже скажу, где это допилить.
            boolean mismatch =
                    !eq(runtime.getExchangeName(), exNew)
                    || runtime.getNetworkType() != netNew
                    || !eq(runtime.getSymbol(), symNew)
                    || !eq(runtime.getTimeframe(), tfNew);

            if (!mismatch) {
                return runtime; // всё ок
            }

            log.warn("🔄 AUTO-RESTART (out-of-sync) chatId={} type={} old=[ex={} net={} sym={} tf={}] new=[ex={} net={} sym={} tf={}]",
                    chatId, type,
                    runtime.getExchangeName(), runtime.getNetworkType(), runtime.getSymbol(), runtime.getTimeframe(),
                    exNew, netNew, symNew, tfNew
            );

            // ✅ атомарный рестарт: stop → start
            // stop должен отписать старый MarketStream (если orchestrator так устроен)
            orchestrator.stopStrategy(chatId, type, exNew, netNew);

            // start заново возьмёт уже новые settings и подпишется на новый symbol/tf
            return orchestrator.startStrategy(chatId, type, exNew, netNew);
        }
    }


    // ================================================================
    // 🔁 TOGGLE
    // ================================================================
    @Override
    @Transactional
    public StrategyRunInfo toggle(Long chatId, StrategyType type, String exchange, NetworkType network) {

        String ex = normExchange(exchange);
        NetworkType net = network;

        if (chatId == null || chatId <= 0 || type == null || ex == null || net == null) {
            log.warn("⚠ TOGGLE пропуск: chatId={} type={} ex={} net={}", chatId, type, exchange, network);
            StrategyRunInfo info = new StrategyRunInfo();
            info.setActive(false);
            info.setExchangeName(ex);
            info.setNetworkType(net);
            return info;
        }

        // ✅ модель: 1 запись на (chatId,type). Берём её и ПАТЧИМ контекст под текущие ex/net.
        StrategySettings settings = null;
        try {
            // если у тебя уже есть этот метод — это самый правильный путь
            settings = settingsService.getOrCreateAndPatchContext(chatId, type, ex, net);
        } catch (Exception ignore) {
            // fallback: если метода ещё нет — используем getOrCreate + patchContext + save
            try {
                settings = settingsService.getOrCreate(chatId, type);
                try {
                    settingsService.patchContext(settings, ex, net);
                } catch (Exception ignored2) {}
                try {
                    settingsService.save(settings);
                } catch (Exception ignored3) {}
            } catch (Exception e) {
                log.warn("⚠ TOGGLE: settings load failed chatId={} type={} ex={} net={} : {}",
                        chatId, type, ex, net, e.getMessage());
            }
        }

        StrategyRunInfo runtime = null;
        boolean isRunning = false;
        try {
            runtime = orchestrator.getStatus(chatId, type, ex, net);
            isRunning = runtime != null && runtime.isActive();
        } catch (Exception e) {
            log.warn("⚠ getStatus failed chatId={} type={} ex={} net={} : {}",
                    chatId, type, ex, net, e.getMessage());
        }

        log.info("🔁 TOGGLE chatId={} type={} running={} ex={} net={} symbol={} tf={}",
                chatId, type, isRunning, ex, net,
                (settings != null ? settings.getSymbol() : null),
                (settings != null ? settings.getTimeframe() : null));

        return isRunning
                ? orchestrator.stopStrategy(chatId, type, ex, net)
                : orchestrator.startStrategy(chatId, type, ex, net);
    }

    // ================================================================
    // ℹ DASHBOARD STATUS
    // ================================================================
    @Override
    @Transactional(readOnly = true)
    public StrategyRunInfo getRunInfo(Long chatId, StrategyType type, String exchange, NetworkType network) {

        String ex = normExchange(exchange);
        NetworkType net = network;

        if (chatId == null || chatId <= 0 || type == null || ex == null || net == null) {
            StrategyRunInfo info = new StrategyRunInfo();
            info.setActive(false);
            info.setExchangeName(ex);
            info.setNetworkType(net);
            return info;
        }

        // ✅ модель: 1 запись на (chatId,type) — её читаем и при необходимости дополняем runtime полями
        StrategySettings s = null;
        try {
            s = settingsService.getSettings(chatId, type);
        } catch (Exception ignored) {}

        StrategyRunInfo runtime = orchestrator.getStatus(chatId, type, ex, net);
        if (runtime == null) {
            runtime = new StrategyRunInfo();
            runtime.setActive(false);
            runtime.setExchangeName(ex);
            runtime.setNetworkType(net);
        }

        if (s != null) {
            runtime.setSymbol(s.getSymbol());
            runtime.setTimeframe(s.getTimeframe());

            // UI любит когда заполнено
            if (runtime.getExchangeName() == null) runtime.setExchangeName(ex);
            if (runtime.getNetworkType() == null) runtime.setNetworkType(net);
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
