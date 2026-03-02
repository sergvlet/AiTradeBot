package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.StrategySettings.CapitalMode;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.events.StrategySettingsUpdatedEvent;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.service.StrategySettingsService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategySettingsServiceImpl implements StrategySettingsService {

    // ✅ defaults через application.properties (можно менять без кода)
    @Value("${strategy.defaults.exchange:BINANCE}")
    private String defaultExchange;

    @Value("${strategy.defaults.network:TESTNET}")
    private String defaultNetworkName;

    @Value("${strategy.defaults.symbol:BTCUSDT}")
    private String defaultSymbol;

    @Value("${strategy.defaults.timeframe:1m}")
    private String defaultTimeframe;

    @Value("${strategy.defaults.minCandles:50}")
    private int minCandles;

    @Value("${strategy.defaults.candles:500}")
    private int defaultCandles;

    private static final String PHASE_LIVE = "LIVE";
    private static final String PHASE_PAPER = "PAPER";
    private static final String PHASE_BACKTEST = "BACKTEST";
    private static final String PHASE_COLLECT = "COLLECT";

    private final StrategySettingsRepository repo;
    private final EntityManager em;
    private final ApplicationEventPublisher events;

    // =====================================================
    // API
    // =====================================================

    @Override
    @Transactional
    public StrategySettings save(StrategySettings s) {
        if (s == null) throw new IllegalArgumentException("StrategySettings is null");
        if (s.getChatId() == null) throw new IllegalArgumentException("chatId is null");
        if (s.getType() == null) throw new IllegalArgumentException("type is null");

        StrategySettings saved = persist(s, false);
        events.publishEvent(new StrategySettingsUpdatedEvent(saved.getChatId(), saved.getType(), "save"));
        return saved;
    }

    /**
     * ✅ ВАЖНО: этот метод нужен тюнеру (persistSafe ищет update(chatId, entity) через reflection).
     * Не ломаем интерфейс StrategySettingsService — метод просто доступен в runtime через proxy.
     */
    @Transactional
    public StrategySettings update(Long chatId, StrategySettings incoming) {
        if (chatId == null || chatId <= 0) throw new IllegalArgumentException("chatId must be positive");
        if (incoming == null) throw new IllegalArgumentException("incoming StrategySettings is null");

        // если вдруг кто-то принёс entity без chatId — подставим
        if (incoming.getChatId() == null || incoming.getChatId() <= 0) {
            incoming.setChatId(chatId);
        } else if (!incoming.getChatId().equals(chatId)) {
            incoming.setChatId(chatId);
        }

        // type обязан быть задан, иначе невозможно сохранить корректно
        if (incoming.getType() == null) {
            throw new IllegalArgumentException("type is null (cannot update StrategySettings without type)");
        }

        StrategySettings saved = persist(incoming, false);
        events.publishEvent(new StrategySettingsUpdatedEvent(saved.getChatId(), saved.getType(), "update"));
        return saved;
    }

    /**
     * ✅ Нужен для fallback в persistSafe(): service.getRepository().save(entity)
     */
    public StrategySettingsRepository getRepository() {
        return repo;
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

        StrategySettings existing = repo.findByChatIdAndType(chatId, type).orElse(null);
        if (existing != null) return existing;

        return createOne(chatId, type);
    }

    @Override
    @Transactional
    public StrategySettings getOrCreateAndPatchContext(long chatId, StrategyType type, String exchange, NetworkType network) {
        StrategySettings s = getOrCreate(chatId, type);
        boolean changed = patchContextInternal(s, exchange, network);
        if (!changed) return s;

        StrategySettings saved = persist(s, false);
        events.publishEvent(new StrategySettingsUpdatedEvent(saved.getChatId(), saved.getType(), "patchContext"));
        return saved;
    }

    @Override
    @Transactional
    public void patchContext(StrategySettings settings, String exchange, NetworkType network) {
        if (settings == null) return;
        boolean changed = patchContextInternal(settings, exchange, network);
        if (!changed) return;

        StrategySettings saved = persist(settings, false);
        events.publishEvent(new StrategySettingsUpdatedEvent(saved.getChatId(), saved.getType(), "patchContext"));
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

                // контекст
                .exchangeName(normalizeExchange(defaultExchange))
                .networkType(defaultNetwork())

                // данные
                .symbol(normalizeSymbol(defaultSymbol))
                .timeframe(normalizeTimeframe(defaultTimeframe))
                .cachedCandlesLimit(Math.max(Math.max(1, minCandles), defaultCandles))

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
            StrategySettings saved = persist(s, true);
            log.info("🆕 Created StrategySettings chatId={} type={} id={} ver={}",
                    chatId, type, saved.getId(), saved.getVersion());

            events.publishEvent(new StrategySettingsUpdatedEvent(saved.getChatId(), saved.getType(), "create"));
            return saved;

        } catch (DataIntegrityViolationException dup) {
            try { em.clear(); } catch (Exception ignored) {}

            StrategySettings existing = repo.findByChatIdAndType(chatId, type).orElse(null);
            if (existing != null) return existing;

            List<StrategySettings> list = repo.findAllByChatIdAndTypeOrderByUpdatedAtDescIdDesc(chatId, type);
            if (!list.isEmpty()) return list.getFirst();

            throw dup;
        }
    }

    private StrategySettings persist(StrategySettings s, boolean flush) {
        normalizeAndDefaults(s);
        applyControlModeFlags(s);

        LocalDateTime now = LocalDateTime.now();
        if (s.getCreatedAt() == null) s.setCreatedAt(now);
        s.setUpdatedAt(now);

        return flush ? repo.saveAndFlush(s) : repo.save(s);
    }

    private boolean patchContextInternal(StrategySettings s, String exchange, NetworkType network) {
        if (s == null) return false;

        String ex = normalizeExchange(exchange);
        NetworkType net = (network != null ? network : defaultNetwork());

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
            s.setSymbol(normalizeSymbol(defaultSymbol));
        } else {
            s.setSymbol(normalizeSymbol(s.getSymbol()));
        }

        if (s.getTimeframe() == null || s.getTimeframe().isBlank()) {
            s.setTimeframe(normalizeTimeframe(defaultTimeframe));
        } else {
            s.setTimeframe(normalizeTimeframe(s.getTimeframe()));
        }

        if (s.getCachedCandlesLimit() == null || s.getCachedCandlesLimit() < Math.max(1, minCandles)) {
            s.setCachedCandlesLimit(Math.max(Math.max(1, minCandles), defaultCandles));
        }

        s.setExchangeName(normalizeExchange(s.getExchangeName()));

        if (s.getNetworkType() == null) {
            s.setNetworkType(defaultNetwork());
        }

        if (s.getAccountAsset() != null) {
            String a = s.getAccountAsset().trim().toUpperCase(Locale.ROOT);
            s.setAccountAsset(a.isEmpty() ? null : a);
        }

        if (s.getCapitalMode() == null) {
            s.setCapitalMode(CapitalMode.ALL);
        }
    }

    /**
     * ✅ Правила режимов:
     * - MANUAL: всегда отключаем авто-тюн и ML gate, phase=LIVE
     * - HYBRID/AI: включаем авто-тюн и ML gate, phase НЕ трогаем (если уже задан),
     *              но если phase пустой -> выставляем безопасный дефолт:
     *              TESTNET => PAPER, MAINNET => LIVE
     *
     * ВАЖНО: мы НЕ должны затирать PHASE_COLLECT/PHASE_BACKTEST.
     * Эти фазы может выставлять рантайм (MlAutoTuneRuntime/AutoTuner) для внутренних процессов.
     */
    private void applyControlModeFlags(StrategySettings s) {
        if (s == null) return;

        AdvancedControlMode mode = s.getAdvancedControlMode();
        if (mode == null) mode = AdvancedControlMode.MANUAL;

        // runPhase: BACKTEST не трогаем. COLLECT больше не выставляем автоматически для AI/HYBRID,
        // иначе ордера не исполняются. Если в БД осталась старая COLLECT — приводим к торговой фазе.
        String rp = (s.getRunPhase() == null) ? "" : s.getRunPhase().trim().toUpperCase(Locale.ROOT);
        boolean phaseIsBacktest = "BACKTEST".equals(rp);

        NetworkType net = (s.getNetworkType() != null) ? s.getNetworkType() : NetworkType.TESTNET;
        String defaultTradingPhase = (net == NetworkType.TESTNET) ? "PAPER" : "LIVE";

        switch (mode) {
            case MANUAL -> {
                s.setAutoTuneEnabled(false);
                s.setMlGateEnabled(false);
                s.setGateMinProb(null);

                if (!phaseIsBacktest && (rp.isEmpty())) {
                    s.setRunPhase("LIVE");
                }
            }
            case HYBRID -> {
                // HYBRID = ML-gate обязателен, автотюн выключен
                s.setAutoTuneEnabled(false);
                s.setMlGateEnabled(true);
                if (s.getGateMinProb() == null) s.setGateMinProb(new BigDecimal("0.55"));

                if (!phaseIsBacktest && (rp.isEmpty() || "COLLECT".equals(rp))) {
                    s.setRunPhase(defaultTradingPhase);
                }
            }
            case AI -> {
                // AI = автотюн обязателен, ML-gate по умолчанию включён
                s.setAutoTuneEnabled(true);
                s.setMlGateEnabled(true);
                if (s.getGateMinProb() == null) s.setGateMinProb(new BigDecimal("0.55"));

                if (!phaseIsBacktest && (rp.isEmpty() || "COLLECT".equals(rp))) {
                    s.setRunPhase(defaultTradingPhase);
                }
            }
            default -> {
                // no-op
            }
        }
    }

    private NetworkType defaultNetwork() {
        try {
            String raw = (defaultNetworkName == null ? "" : defaultNetworkName.trim().toUpperCase(Locale.ROOT));
            if (raw.isEmpty()) return NetworkType.TESTNET;
            return NetworkType.valueOf(raw);
        } catch (Exception e) {
            return NetworkType.TESTNET;
        }
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null) return "BTCUSDT";
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? "BTCUSDT" : s;
    }

    private static String normalizeTimeframe(String timeframe) {
        if (timeframe == null) return "1m";
        String tf = timeframe.trim().toLowerCase(Locale.ROOT);
        return tf.isEmpty() ? "1m" : tf;
    }

    private static String normalizeExchange(String exchange) {
        if (exchange == null) return "BINANCE";
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        return ex.isEmpty() ? "BINANCE" : ex;
    }

    private static String normalizeUpperNullable(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v.toUpperCase(Locale.ROOT);
    }

    @Override
    @Transactional(readOnly = true)
    public Long getVersion(Long chatId, StrategyType type) {
        if (chatId == null || type == null) return null;
        Integer v = repo.findVersion(chatId, type);
        return v != null ? v.longValue() : null;
    }
}
