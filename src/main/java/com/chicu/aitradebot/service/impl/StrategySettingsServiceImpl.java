package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.StrategySettings.CapitalMode;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.service.StrategySettingsService;
import jakarta.persistence.EntityManager;
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
    private static final NetworkType DEFAULT_NETWORK = NetworkType.TESTNET;

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final String DEFAULT_TIMEFRAME = "1m";
    private static final int MIN_CANDLES = 50;
    private static final int DEFAULT_CANDLES = 500;

    private static final String PHASE_LIVE = "LIVE";
    private static final String PHASE_PAPER = "PAPER";
    private static final String PHASE_BACKTEST = "BACKTEST";
    private static final String PHASE_COLLECT = "COLLECT";

    private final StrategySettingsRepository repo;
    private final EntityManager em;

    // =====================================================
    // API
    // =====================================================

    @Override
    @Transactional
    public StrategySettings save(StrategySettings s) {
        if (s == null) throw new IllegalArgumentException("StrategySettings is null");
        if (s.getChatId() == null) throw new IllegalArgumentException("chatId is null");
        if (s.getType() == null) throw new IllegalArgumentException("type is null");

        normalizeAndDefaults(s);
        applyControlModeFlags(s);

        LocalDateTime now = LocalDateTime.now();
        if (s.getCreatedAt() == null) s.setCreatedAt(now);
        s.setUpdatedAt(now);

        return repo.save(s);
    }

    @Override
    @Transactional(readOnly = true)
    public StrategySettings getSettings(long chatId, StrategyType type) {
        if (chatId <= 0 || type == null) return null;
        return repo.findByChatIdAndType(chatId, type).orElse(null);
    }

    @Override
    @Transactional
    public StrategySettings getOrCreate(long chatId, StrategyType type) {
        if (chatId <= 0) throw new IllegalArgumentException("chatId must be positive");
        if (type == null) throw new IllegalArgumentException("type must be provided");

        // ✅ можно сразу lock-методом — меньше гонок от autosave
        return repo.findByChatIdAndTypeForUpdate(chatId, type)
                .orElseGet(() -> createOne(chatId, type));
    }

    @Override
    @Transactional
    public StrategySettings getOrCreateAndPatchContext(long chatId, StrategyType type, String exchange, NetworkType network) {
        StrategySettings s = getOrCreate(chatId, type);
        boolean changed = patchContextInternal(s, exchange, network);
        if (changed) {
            s = repo.save(s);
        }
        return s;
    }

    @Override
    @Transactional
    public void patchContext(StrategySettings settings, String exchange, NetworkType network) {
        if (settings == null) return;
        boolean changed = patchContextInternal(settings, exchange, network);
        if (changed) repo.save(settings);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StrategySettings> findAllByChatId(long chatId) {
        return repo.findAllByChatId(chatId);
    }

    // =====================================================
    // INTERNALS
    // =====================================================

    private StrategySettings createOne(long chatId, StrategyType type) {
        LocalDateTime now = LocalDateTime.now();

        StrategySettings s = StrategySettings.builder()
                .chatId(chatId)
                .type(type)

                // контекст (не ключ)
                .exchangeName(DEFAULT_EXCHANGE)
                .networkType(DEFAULT_NETWORK)

                // данные
                .symbol(DEFAULT_SYMBOL)
                .timeframe(DEFAULT_TIMEFRAME)
                .cachedCandlesLimit(DEFAULT_CANDLES)

                // риск/капитал
                .capitalMode(CapitalMode.ALL)
                .capitalValue(null)

                // управление
                .advancedControlMode(AdvancedControlMode.MANUAL)
                .autoTuneEnabled(false)
                .mlGateEnabled(false)
                .runPhase(PHASE_LIVE)

                .active(false)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            StrategySettings saved = repo.saveAndFlush(s);
            log.info("🆕 Created StrategySettings chatId={} type={} id={}", chatId, type, saved.getId());
            return saved;

        } catch (DataIntegrityViolationException dup) {
            // ✅ критично для Hibernate: после ошибки уникальности чистим persistence context
            try { em.clear(); } catch (Exception ignored) {}

            // если уже есть запись — возвращаем её (или “самую свежую”, если были дубли)
            StrategySettings existing = repo.findByChatIdAndType(chatId, type).orElse(null);
            if (existing != null) return existing;

            List<StrategySettings> list = repo.findAllByChatIdAndTypeOrderByUpdatedAtDescIdDesc(chatId, type);
            if (!list.isEmpty()) return list.getFirst();

            throw dup;
        }
    }

    private boolean patchContextInternal(StrategySettings s, String exchange, NetworkType network) {
        if (s == null) return false;

        String ex = normalizeExchange(exchange);
        NetworkType net = (network != null ? network : DEFAULT_NETWORK);

        boolean changed = false;

        if (s.getExchangeName() == null || !s.getExchangeName().equals(ex)) {
            s.setExchangeName(ex);
            changed = true;
        }
        if (s.getNetworkType() == null || s.getNetworkType() != net) {
            s.setNetworkType(net);
            changed = true;
        }
        return changed;
    }

    private void normalizeAndDefaults(StrategySettings s) {
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

        s.setExchangeName(normalizeExchange(s.getExchangeName()));

        if (s.getNetworkType() == null) {
            s.setNetworkType(DEFAULT_NETWORK);
        }

        if (s.getAccountAsset() != null) {
            String a = s.getAccountAsset().trim().toUpperCase(Locale.ROOT);
            s.setAccountAsset(a.isEmpty() ? null : a);
        }

        if (s.getCapitalMode() == null) {
            s.setCapitalMode(CapitalMode.ALL);
        }
        // capitalValue нормализуется в @PrePersist/@PreUpdate (у тебя это есть)
    }

    private void applyControlModeFlags(StrategySettings s) {
        AdvancedControlMode mode = (s.getAdvancedControlMode() != null)
                ? s.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;

        String phase = normalizeUpperNullable(s.getRunPhase());

        switch (mode) {
            case MANUAL -> {
                s.setAutoTuneEnabled(false);
                s.setMlGateEnabled(false);
                s.setRunPhase(PHASE_LIVE);
            }
            case HYBRID -> {
                s.setAutoTuneEnabled(true);
                s.setMlGateEnabled(true);
                if (phase == null || PHASE_BACKTEST.equals(phase) || PHASE_COLLECT.equals(phase)) {
                    s.setRunPhase(PHASE_LIVE);
                } else {
                    s.setRunPhase(phase);
                }
            }
            case AI -> {
                s.setAutoTuneEnabled(true);
                s.setMlGateEnabled(true);
                if (phase == null || PHASE_BACKTEST.equals(phase) || PHASE_COLLECT.equals(phase)) {
                    s.setRunPhase(PHASE_LIVE);
                } else {
                    s.setRunPhase(phase);
                }
            }
        }
    }

    private static String normalizeExchange(String exchange) {
        if (exchange == null) return DEFAULT_EXCHANGE;
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        return ex.isEmpty() ? DEFAULT_EXCHANGE : ex;
    }

    private static String normalizeUpperNullable(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v.toUpperCase(Locale.ROOT);
    }
}
