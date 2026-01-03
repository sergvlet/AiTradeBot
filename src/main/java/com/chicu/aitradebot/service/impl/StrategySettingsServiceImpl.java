package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.service.StrategySettingsService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategySettingsServiceImpl implements StrategySettingsService {

    private final StrategySettingsRepository repo;

    /**
     * Кэш используется ТОЛЬКО при полном контексте:
     * chatId + type + exchange + network
     */
    private final Map<String, StrategySettings> cache = new ConcurrentHashMap<>();

    // =====================================================================
    // CACHE KEY
    // =====================================================================
    private String key(long chatId, StrategyType type, String exchange, NetworkType network) {
        return chatId + ":" + type.name() + ":" + exchange + ":" + network;
    }

    // =====================================================================
    // SAVE (очищает старый cache)
    // =====================================================================
    @Override
    public StrategySettings save(StrategySettings s) {

        if (s.getAdvancedControlMode() == null) {
            s.setAdvancedControlMode(AdvancedControlMode.MANUAL);
        }

        StrategySettings saved = repo.save(s);

        // ❗ сбрасываем все старые ключи этого chatId+type
        cache.entrySet().removeIf(e ->
                e.getKey().startsWith(saved.getChatId() + ":" + saved.getType().name() + ":")
        );

        // кладём актуальный
        if (saved.getExchangeName() != null && saved.getNetworkType() != null) {
            cache.put(
                    key(
                            saved.getChatId(),
                            saved.getType(),
                            saved.getExchangeName(),
                            saved.getNetworkType()
                    ),
                    saved
            );
        }

        return saved;
    }

    // =====================================================================
    // FIND ALL (UI)
    // =====================================================================
    @Override
    public List<StrategySettings> findAllByChatId(
            long chatId,
            String exchange,
            NetworkType network
    ) {
        return repo.findByChatId(chatId).stream()
                .filter(s -> exchange == null || exchange.equals(s.getExchangeName()))
                .filter(s -> network == null || network == s.getNetworkType())
                .toList();
    }

    // =====================================================================
    // GET (legacy)
    // =====================================================================
    @Override
    @Deprecated
    public StrategySettings getSettings(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {

        if (exchange == null || network == null) {
            return repo
                    .findTopByChatIdAndTypeOrderByUpdatedAtDescIdDesc(chatId, type)
                    .orElse(null);
        }

        String k = key(chatId, type, exchange, network);

        StrategySettings cached = cache.get(k);
        if (cached != null) {
            return cached;
        }

        return repo
                .findTopByChatIdAndTypeAndExchangeNameAndNetworkTypeOrderByUpdatedAtDescIdDesc(
                        chatId, type, exchange, network
                )
                .map(s -> {
                    cache.put(k, s);
                    return s;
                })
                .orElse(null);
    }

    // =====================================================================
    // GET OR CREATE (🔥 ОСНОВНОЙ ФИКС)
    // =====================================================================
    @Override
    public StrategySettings getOrCreate(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {
        // 1️⃣ Пытаемся найти точное совпадение
        StrategySettings exact = getSettings(chatId, type, exchange, network);
        if (exact != null) {
            log.info("✅ Найдена существующая StrategySettings (id={}) с полным контекстом", exact.getId());
            return exact;
        }

        // 2️⃣ fallback — последняя стратегия этого типа (без учёта exchange/network)
        Optional<StrategySettings> fallback =
                repo.findTopByChatIdAndTypeOrderByUpdatedAtDescIdDesc(chatId, type);

        if (fallback.isPresent()) {
            StrategySettings s = fallback.get();
            boolean changed = false;

            if (s.getExchangeName() == null && exchange != null) {
                s.setExchangeName(exchange);
                changed = true;
            }

            if (s.getNetworkType() == null && network != null) {
                s.setNetworkType(network);
                changed = true;
            }

            if (changed) {
                log.info("♻️ Повторное использование StrategySettings id={} с обновлением контекста", s.getId());
                return save(s);
            }

            log.info("✅ Найдена StrategySettings id={} без изменений", s.getId());
            return s;
        }

        // 3️⃣ Новый объект (если вообще ничего нет)
        log.warn(
                "🆕 Создаётся новая StrategySettings chatId={}, type={}, exchange={}, network={}",
                chatId, type, exchange, network
        );

        StrategySettings s = StrategySettings.builder()
                .chatId(chatId)
                .type(type)
                .symbol("BTCUSDT")
                .timeframe("1m")
                .cachedCandlesLimit(500)
                .capitalUsd(BigDecimal.valueOf(100))
                .commissionPct(BigDecimal.valueOf(0.05))
                .riskPerTradePct(BigDecimal.valueOf(1))
                .dailyLossLimitPct(BigDecimal.valueOf(20))
                .takeProfitPct(BigDecimal.valueOf(1))
                .stopLossPct(BigDecimal.valueOf(1))
                .reinvestProfit(false)
                .exchangeName(exchange)
                .networkType(network)
                .advancedControlMode(AdvancedControlMode.MANUAL)
                .active(false)
                .build();

        return save(s);
    }


    // =====================================================================
    // FIND LATEST
    // =====================================================================
    @Override
    public Optional<StrategySettings> findLatest(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {
        boolean hasExchange = exchange != null && !exchange.isBlank();
        boolean hasNetwork  = network != null;

        if (hasExchange && hasNetwork) {
            return repo.findTopByChatIdAndTypeAndExchangeNameAndNetworkTypeOrderByUpdatedAtDescIdDesc(
                    chatId, type, exchange, network
            );
        }

        return repo.findTopByChatIdAndTypeOrderByUpdatedAtDescIdDesc(chatId, type);
    }

    // =====================================================================
    // UPDATE RISK FROM UI
    // =====================================================================
    @Override
    @Transactional
    public void updateRiskFromUi(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            BigDecimal dailyLossLimitPct,
            BigDecimal riskPerTradePct
    ) {
        StrategySettings s = getOrCreate(chatId, type, exchange, network);

        s.setDailyLossLimitPct(validatePct(dailyLossLimitPct));
        s.setRiskPerTradePct(validatePct(riskPerTradePct));

        save(s);
    }

    // =====================================================================
    // UPDATE RISK FROM AI
    // =====================================================================
    @Override
    @Transactional
    public void updateRiskFromAi(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            BigDecimal newRiskPerTradePct
    ) {
        StrategySettings s = getOrCreate(chatId, type, exchange, network);

        if (s.getAdvancedControlMode() == AdvancedControlMode.MANUAL) {
            return;
        }

        BigDecimal incoming = validatePct(newRiskPerTradePct);
        BigDecimal current = s.getRiskPerTradePct();

        if (current == null || incoming.compareTo(current) < 0) {
            s.setRiskPerTradePct(incoming);
            save(s);
        }
    }

    // =====================================================================
    // VALIDATION
    // =====================================================================
    private BigDecimal validatePct(BigDecimal v) {
        if (v == null) return null;

        if (v.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }

        if (v.compareTo(BigDecimal.valueOf(100)) > 0) {
            return BigDecimal.valueOf(100);
        }

        return v.setScale(4, RoundingMode.HALF_UP);
    }

    @Override
    public List<StrategySettings> findAllByChatId(long chatId, String exchange) {
        return repo.findByChatId(chatId).stream()
                .filter(s -> exchange == null || exchange.equals(s.getExchangeName()))
                .toList();
    }

}
