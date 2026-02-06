package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.StrategySettings.CapitalMode;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategySettingsServiceImpl implements StrategySettingsService {

    private static final String DEFAULT_EXCHANGE = "BINANCE";
    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final String DEFAULT_TIMEFRAME = "1m";
    private static final int    MIN_CANDLES = 50;
    private static final int    DEFAULT_CANDLES = 500;

    private static final String PHASE_LIVE = "LIVE";
    private static final String PHASE_PAPER = "PAPER";
    private static final String PHASE_BACKTEST = "BACKTEST";
    private static final String PHASE_COLLECT = "COLLECT";

    private final StrategySettingsRepository repo;

    @Override
    @Transactional
    public StrategySettings save(StrategySettings s) {
        if (s == null) throw new IllegalArgumentException("StrategySettings is null");

        // --- дефолты/нормализация обязательных полей ---
        if (s.getAdvancedControlMode() == null) {
            s.setAdvancedControlMode(AdvancedControlMode.MANUAL);
        }

        if (s.getSymbol() == null || s.getSymbol().isBlank()) {
            s.setSymbol(DEFAULT_SYMBOL);
        } else {
            s.setSymbol(s.getSymbol().trim().toUpperCase(Locale.ROOT));
        }

        if (s.getTimeframe() == null || s.getTimeframe().isBlank()) {
            s.setTimeframe(DEFAULT_TIMEFRAME);
        } else {
            s.setTimeframe(s.getTimeframe().trim().toLowerCase(Locale.ROOT));
        }

        if (s.getCachedCandlesLimit() == null || s.getCachedCandlesLimit() < MIN_CANDLES) {
            s.setCachedCandlesLimit(DEFAULT_CANDLES);
        }

        if (s.getExchangeName() != null) {
            String ex = s.getExchangeName().trim().toUpperCase(Locale.ROOT);
            s.setExchangeName(ex.isEmpty() ? DEFAULT_EXCHANGE : ex);
        } else {
            s.setExchangeName(DEFAULT_EXCHANGE);
        }

        if (s.getAccountAsset() != null) {
            String a = s.getAccountAsset().trim().toUpperCase(Locale.ROOT);
            s.setAccountAsset(a.isEmpty() ? null : a);
        }

        // --- единая логика режима управления (флаги + безопасная фаза) ---
        applyControlModeFlags(s);

        // --- капитал: если mode null -> ALL; value в StrategySettings нормализуется lifecycle-методами,
        // но здесь дополнительно защитимся от null mode ---
        if (s.getCapitalMode() == null) {
            s.setCapitalMode(CapitalMode.ALL);
        }

        // timestamps
        LocalDateTime now = LocalDateTime.now();
        if (s.getCreatedAt() == null) s.setCreatedAt(now);
        s.setUpdatedAt(now);

        return repo.save(s);
    }

    /**
     * MANUAL/HYBRID/AI => системные флаги исполнения.
     * ВАЖНО: сервис не должен “сам” менять сеть/режим торговли.
     * Он лишь гарантирует консистентность: флаги и безопасная runPhase.
     */
    private void applyControlModeFlags(StrategySettings s) {
        AdvancedControlMode mode = (s.getAdvancedControlMode() != null)
                ? s.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;

        // runPhase нормализуем “бережно”:
        // - BACKTEST/ COLLECT не делаем дефолтом здесь
        // - PAPER допускаем, но только если кто-то (UI) явно поставил
        String phase = normalizeUpperNullable(s.getRunPhase());

        switch (mode) {
            case MANUAL -> {
                s.setAutoTuneEnabled(false);
                s.setMlGateEnabled(false);
                // MANUAL всегда “обычная работа”
                s.setRunPhase(PHASE_LIVE);
            }
            case HYBRID -> {
                s.setAutoTuneEnabled(true);
                s.setMlGateEnabled(true);
                // HYBRID по умолчанию LIVE (а PAPER/LIVE решает контроллер/UI по network)
                if (phase == null || PHASE_BACKTEST.equals(phase) || PHASE_COLLECT.equals(phase)) {
                    s.setRunPhase(PHASE_LIVE);
                } else {
                    s.setRunPhase(phase);
                }
            }
            case AI -> {
                s.setAutoTuneEnabled(true);
                s.setMlGateEnabled(true);
                // AI: если UI не поставил PAPER явно — держим LIVE
                if (phase == null || PHASE_BACKTEST.equals(phase) || PHASE_COLLECT.equals(phase)) {
                    s.setRunPhase(PHASE_LIVE);
                } else {
                    s.setRunPhase(phase);
                }
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public StrategySettings getSettings(long chatId, StrategyType type, String exchange, NetworkType network) {
        if (chatId <= 0 || type == null || network == null) return null;
        String ex = normalizeExchange(exchange);
        return repo.findByChatIdAndTypeAndExchangeNameAndNetworkType(chatId, type, ex, network).orElse(null);
    }

    @Override
    @Transactional
    public StrategySettings getOrCreate(long chatId, StrategyType type, String exchange, NetworkType network) {
        if (chatId <= 0) throw new IllegalArgumentException("chatId must be positive");
        if (type == null) throw new IllegalArgumentException("type must be provided");
        if (network == null) throw new IllegalArgumentException("network must be provided");

        String ex = normalizeExchange(exchange);

        return repo.findByChatIdAndTypeAndExchangeNameAndNetworkType(chatId, type, ex, network)
                .orElseGet(() -> createOne(chatId, type, ex, network));
    }

    private StrategySettings createOne(long chatId, StrategyType type, String exchange, NetworkType network) {
        LocalDateTime now = LocalDateTime.now();

        StrategySettings s = StrategySettings.builder()
                .chatId(chatId)
                .type(type)
                .exchangeName(exchange)
                .networkType(network)
                .active(false)
                .advancedControlMode(AdvancedControlMode.MANUAL)
                .runPhase(PHASE_LIVE)

                // дефолты торговли/данных
                .symbol(DEFAULT_SYMBOL)
                .timeframe(DEFAULT_TIMEFRAME)
                .cachedCandlesLimit(DEFAULT_CANDLES)

                // ✅ новый риск-контроль: по умолчанию “весь баланс”
                .capitalMode(CapitalMode.ALL)
                .capitalValue(null)

                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            StrategySettings saved = repo.save(s);
            log.info("🆕 Created StrategySettings chatId={} type={} ex={} net={} id={}",
                    chatId, type, exchange, network, saved.getId());
            return saved;
        } catch (DataIntegrityViolationException dup) {
            return repo.findByChatIdAndTypeAndExchangeNameAndNetworkType(chatId, type, exchange, network)
                    .orElseThrow(() -> dup);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<StrategySettings> findAllByChatId(long chatId, String exchange, NetworkType network) {
        String ex = normalizeExchange(exchange);
        if (network == null) {
            return repo.findAllByChatIdAndExchangeName(chatId, ex);
        }
        return repo.findAllByChatIdAndExchangeNameAndNetworkType(chatId, ex, network);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StrategySettings> findAllByChatId(long chatId, String exchange) {
        String ex = normalizeExchange(exchange);
        return repo.findAllByChatIdAndExchangeName(chatId, ex);
    }

    // ✅ Старые методы riskPerTradePct/dailyLossLimitPct УДАЛЯЕМ из интерфейса StrategySettingsService.
    // Здесь их больше не реализуем, потому что полей больше нет и это будет “тихий” баг.

    private static String normalizeExchange(String exchange) {
        if (exchange == null) return DEFAULT_EXCHANGE;
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        return ex.isEmpty() ? DEFAULT_EXCHANGE : ex;
    }

    private static String normalizeUpperNullable(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        return v.toUpperCase(Locale.ROOT);
    }
}
