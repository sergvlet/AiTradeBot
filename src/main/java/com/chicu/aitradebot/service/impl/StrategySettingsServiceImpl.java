package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategySettingsServiceImpl implements StrategySettingsService {

    private final StrategySettingsRepository repo;

    /**
     * ✅ КЭШ: (chatId:type) -> StrategySettings
     * Убирает постоянные SELECT в live-пайплайне.
     */
    private final Map<String, StrategySettings> cache = new ConcurrentHashMap<>();

    private String key(long chatId, StrategyType type) {
        return chatId + ":" + type.name();
    }

    @Override
    public StrategySettings save(StrategySettings s) {
        StrategySettings saved = repo.save(s);

        // обновляем кэш
        if (saved.getChatId() != null && saved.getType() != null) {
            cache.put(key(saved.getChatId(), saved.getType()), saved);
        }

        return saved;
    }

    @Override
    public List<StrategySettings> findAllByChatId(long chatId) {
        // (по желанию можно тут прогревать кэш, но не обязательно)
        return repo.findByChatId(chatId);
    }

    @Override
    public StrategySettings getSettings(long chatId, StrategyType type) {

        // 1) сперва кэш
        StrategySettings cached = cache.get(key(chatId, type));
        if (cached != null) {
            return cached;
        }

        // 2) потом БД
        List<StrategySettings> list = repo.findByChatIdAndType(chatId, type);

        if (list == null || list.isEmpty()) {
            return null;
        }

        if (list.size() > 1) {
            log.error("❌ НАЙДЕНО {} StrategySettings для chatId={} type={}. Лишние будут удалены.",
                    list.size(), chatId, type);

            StrategySettings keep = list.get(0);

            // удаляем дубликаты
            for (int i = 1; i < list.size(); i++) {
                try {
                    repo.delete(list.get(i));
                } catch (Exception e) {
                    log.warn("⚠ Не удалось удалить дубликат StrategySettings id={}: {}",
                            list.get(i).getId(), e.getMessage());
                }
            }

            // кладём то, что оставили
            cache.put(key(chatId, type), keep);
            return keep;
        }

        StrategySettings one = list.get(0);
        cache.put(key(chatId, type), one);
        return one;
    }

    @Override
    public StrategySettings getOrCreate(long chatId, StrategyType type) {

        // 1) сперва кэш
        String k = key(chatId, type);
        StrategySettings cached = cache.get(k);
        if (cached != null) {
            return cached;
        }

        // 2) потом поиск (и чистка дублей) через getSettings
        StrategySettings existing = getSettings(chatId, type);
        if (existing != null) {
            cache.put(k, existing);
            return existing;
        }

        // 3) создать дефолт
        log.warn("⚠ Создаём новый StrategySettings (chatId={}, type={})", chatId, type);

        StrategySettings s = StrategySettings.builder()
                .chatId(chatId)
                .type(type)

                // базовые торговые настройки
                .symbol("BTCUSDT")
                .timeframe("1m")
                .cachedCandlesLimit(500)

                // капитал / риск
                .capitalUsd(BigDecimal.valueOf(100))
                .commissionPct(BigDecimal.valueOf(0.05))
                .takeProfitPct(BigDecimal.valueOf(1))
                .stopLossPct(BigDecimal.valueOf(1))
                .riskPerTradePct(BigDecimal.valueOf(1))
                .dailyLossLimitPct(BigDecimal.valueOf(20))
                .reinvestProfit(false)
                .leverage(1)

                // PnL / ML
                .totalProfitPct(BigDecimal.ZERO)
                .mlConfidence(BigDecimal.ZERO)

                // биржа/сеть по умолчанию — BINANCE TESTNET
                .exchangeName("BINANCE")
                .networkType(NetworkType.TESTNET)

                .active(false)
                .build();

        StrategySettings saved = repo.save(s);

        // 4) в кэш
        cache.put(k, saved);

        return saved;
    }
}
