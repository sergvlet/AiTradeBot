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

        log.info("📋 getStrategies chatId={} exchange={} network={}", chatId, exchange, network);

        // ✅ Берём все настройки по chatId (+ exchange фильтр), сеть фильтруем тут
        List<StrategySettings> all = settingsService.findAllByChatId(chatId, exchange);

        // ✅ В проде теперь UNIQUE, но список всё равно может быть:
        // - разный network
        // - разные стратегии
        // Поэтому группируем по type и берём "последнюю" по id (на всякий случай).
        Map<StrategyType, StrategySettings> latestByType = new EnumMap<>(StrategyType.class);

        for (StrategySettings s : all) {
            if (s == null || s.getType() == null) continue;

            // если network задан — показываем только эту сеть
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
                // если нет записи — рисуем empty (сеть берём из параметра страницы)
                result.add(StrategyUi.empty(chatId, type, exchange, network));
                continue;
            }

            boolean active = false;
            try {
                StrategyRunInfo runtime = orchestrator.getStatus(
                        chatId,
                        type,
                        exchange,
                        settings.getNetworkType()
                );
                active = runtime != null && runtime.isActive();
            } catch (Exception e) {
                log.warn("⚠ getStatus failed type={} chatId={} : {}", type, chatId, e.getMessage());
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

        // ✅ никаких findLatest(...) — только getSettings/getOrCreate
        // toggle не должен плодить записи при каждом клике, поэтому:
        // 1) пробуем прочитать
        // 2) если нет — создаём (после твоего DROP это обязательно)
        StrategySettings settings = settingsService.getSettings(chatId, type, exchange, network);
        if (settings == null) {
            settings = settingsService.getOrCreate(chatId, type, exchange, network);
        }

        StrategyRunInfo runtime = orchestrator.getStatus(chatId, type, exchange, network);
        boolean isRunning = runtime != null && runtime.isActive();

        log.info("🔁 TOGGLE chatId={} type={} running={} symbol={} tf={} ex={} net={}",
                chatId, type, isRunning, settings.getSymbol(), settings.getTimeframe(), exchange, network);

        // ✅ старт/стоп — только runtime (orchestrator сам сохранит active/startedAt/stoppedAt)
        return isRunning
                ? orchestrator.stopStrategy(chatId, type, exchange, network)
                : orchestrator.startStrategy(chatId, type, exchange, network);
    }

    // ================================================================
    // ℹ DASHBOARD STATUS
    // ================================================================
    @Override
    @Transactional(readOnly = true)
    public StrategyRunInfo getRunInfo(Long chatId, StrategyType type, String exchange, NetworkType network) {

        // ✅ вместо findLatest(...)
        StrategySettings s = settingsService.getSettings(chatId, type, exchange, network);
        if (s == null) return null;

        StrategyRunInfo runtime = orchestrator.getStatus(chatId, type, exchange, network);
        if (runtime == null) return null;

        // ✅ то, что точно живёт в StrategySettings
        runtime.setSymbol(s.getSymbol());
        runtime.setTimeframe(s.getTimeframe());

        return runtime;
    }
}
