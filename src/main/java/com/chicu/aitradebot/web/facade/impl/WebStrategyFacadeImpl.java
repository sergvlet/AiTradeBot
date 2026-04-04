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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebStrategyFacadeImpl implements WebStrategyFacade {

    private final AiStrategyOrchestrator orchestrator;
    private final StrategySettingsService settingsService;

    @Override
    @Transactional(readOnly = true)
    public List<StrategyUi> getStrategies(Long chatId, String exchange, NetworkType network) {

        String exFilter = normExchange(exchange);

        log.info("📋 getStrategies chatId={} exchange={} (norm={}) network={}",
                chatId, exchange, exFilter, network);

        List<StrategySettings> all = settingsService.findAllByChatId(chatId);

        Map<StrategyType, StrategySettings> byType = new EnumMap<>(StrategyType.class);
        for (StrategySettings s : all) {
            if (s == null || s.getType() == null) continue;

            if (network != null && s.getNetworkType() != network) continue;

            if (exFilter != null) {
                String exFromDb = normExchange(s.getExchangeName());
                if (exFromDb == null || !exFilter.equals(exFromDb)) continue;
            }

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
                result.add(StrategyUi.empty(chatId, type, exFilter, network));
                continue;
            }

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
            String tfNew = normUpper(s.getTimeframe());

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

            if (runtime == null || !runtime.isActive()) {
                return runtime != null ? runtime : new StrategyRunInfo();
            }

            boolean mismatch =
                    !eq(runtime.getExchangeName(), exNew)
                    || runtime.getNetworkType() != netNew
                    || !eq(runtime.getSymbol(), symNew)
                    || !eq(runtime.getTimeframe(), tfNew);

            if (!mismatch) {
                return runtime;
            }

            log.warn("🔄 AUTO-RESTART (out-of-sync) chatId={} type={} old=[ex={} net={} sym={} tf={}] new=[ex={} net={} sym={} tf={}]",
                    chatId, type,
                    runtime.getExchangeName(), runtime.getNetworkType(), runtime.getSymbol(), runtime.getTimeframe(),
                    exNew, netNew, symNew, tfNew
            );

            return orchestrator.restartStrategyAtomic(chatId, type, exNew, netNew, "web_out_of_sync");
        }
    }

    @Override
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

        StrategySettings settings = null;
        try {
            settings = settingsService.getOrCreateAndPatchContext(chatId, type, ex, net);
        } catch (Exception ignore) {
            try {
                settings = settingsService.getOrCreate(chatId, type);
                try {
                    settingsService.patchContext(settings, ex, net);
                } catch (Exception ignored2) {
                }
                try {
                    settingsService.save(settings);
                } catch (Exception ignored3) {
                }
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

        StrategySettings s = null;
        try {
            s = settingsService.getSettings(chatId, type);
        } catch (Exception ignored) {
        }

        StrategyRunInfo runtime = orchestrator.getStatus(chatId, type, ex, net);
        if (runtime == null) {
            runtime = new StrategyRunInfo();
            runtime.setActive(false);
            runtime.setExchangeName(ex);
            runtime.setNetworkType(net);
        }

        if (s != null) {
            if (runtime.getSymbol() == null) {
                runtime.setSymbol(s.getSymbol());
            }
            if (runtime.getTimeframe() == null) {
                runtime.setTimeframe(s.getTimeframe());
            }

            if (runtime.getExchangeName() == null) runtime.setExchangeName(ex);
            if (runtime.getNetworkType() == null) runtime.setNetworkType(net);
        }

        return runtime;
    }

    private static String normExchange(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}
