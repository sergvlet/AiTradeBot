package com.chicu.aitradebot.strategy.windowscalping;

import com.chicu.aitradebot.events.WindowScalpingSettingsUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class WindowScalpingStrategySettingsServiceImpl implements WindowScalpingStrategySettingsService {

    private final WindowScalpingStrategySettingsRepository repo;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional
    public WindowScalpingStrategySettings getOrCreate(Long chatId) {
        if (chatId == null || chatId <= 0) throw new IllegalArgumentException("chatId must be positive");

        WindowScalpingStrategySettings existing = repo.findByChatId(chatId).orElse(null);
        if (existing != null) return existing;

        try {
            WindowScalpingStrategySettings def = WindowScalpingStrategySettings.builder()
                    .chatId(chatId)
                    .build();

            WindowScalpingStrategySettings saved = repo.saveAndFlush(def);

            log.info("🆕 WINDOW_SCALPING settings created (chatId={}, id={})", chatId, saved.getId());
            publishAfterCommit(new WindowScalpingSettingsUpdatedEvent(chatId, "create"));

            return saved;

        } catch (DataIntegrityViolationException dup) {
            // гонка: кто-то создал параллельно
            return repo.findByChatId(chatId).orElseThrow(() -> dup);
        }
    }

    @Override
    @Transactional
    public WindowScalpingStrategySettings update(Long chatId, WindowScalpingStrategySettings incoming) {
        if (chatId == null || chatId <= 0) throw new IllegalArgumentException("chatId must be positive");
        if (incoming == null) throw new IllegalArgumentException("incoming is null");

        WindowScalpingStrategySettings cur = getOrCreate(chatId);

        // ✅ ВАЖНО:
        // patchMode=true => incoming обычно собран через builder() и несёт @Builder.Default.
        // Чтобы НЕ перетирать существующие значения дефолтами — применяем только то,
        // что реально отличается от дефолтов.
        boolean patchMode = (incoming.getId() == null);

        WindowScalpingStrategySettings defaults = WindowScalpingStrategySettings.builder()
                .chatId(chatId)
                .build();

        boolean changed = false;

        // ✅ TP/SL
        BigDecimal tp = incoming.getTakeProfitPct();
        if (tp != null && tp.signum() > 0) {
            if (!patchMode || !bdEquals(tp, defaults.getTakeProfitPct())) {
                if (!bdEquals(tp, cur.getTakeProfitPct())) {
                    cur.setTakeProfitPct(tp);
                    changed = true;
                }
            }
        }

        BigDecimal sl = incoming.getStopLossPct();
        if (sl != null && sl.signum() > 0) {
            if (!patchMode || !bdEquals(sl, defaults.getStopLossPct())) {
                if (!bdEquals(sl, cur.getStopLossPct())) {
                    cur.setStopLossPct(sl);
                    changed = true;
                }
            }
        }

        // ✅ WINDOW поля
        Integer ws = incoming.getWindowSize();
        if (ws != null && ws >= 5) {
            if (!patchMode || !Objects.equals(ws, defaults.getWindowSize())) {
                if (!Objects.equals(ws, cur.getWindowSize())) {
                    cur.setWindowSize(ws);
                    changed = true;
                }
            }
        }

        Double low = incoming.getEntryFromLowPct();
        if (low != null) {
            double v = clamp(low, 0.0, 100.0);
            if (!patchMode || !dblEquals(v, safeD(defaults.getEntryFromLowPct()))) {
                if (!dblEquals(v, safeD(cur.getEntryFromLowPct()))) {
                    cur.setEntryFromLowPct(v);
                    changed = true;
                }
            }
        }

        Double high = incoming.getEntryFromHighPct();
        if (high != null) {
            double v = clamp(high, 0.0, 100.0);
            if (!patchMode || !dblEquals(v, safeD(defaults.getEntryFromHighPct()))) {
                if (!dblEquals(v, safeD(cur.getEntryFromHighPct()))) {
                    cur.setEntryFromHighPct(v);
                    changed = true;
                }
            }
        }

        // ✅ minRangePct:
        //  - допускаем 0.0 (это будем трактовать как AUTO в стратегии)
        //  - в patchMode НЕ перетираем cur дефолтом из builder()
        Double minRange = incoming.getMinRangePct();
        if (minRange != null) {
            double v = clamp(minRange, 0.0, 100.0);
            if (!patchMode || !dblEquals(v, safeD(defaults.getMinRangePct()))) {
                if (!dblEquals(v, safeD(cur.getMinRangePct()))) {
                    cur.setMinRangePct(v);
                    changed = true;
                }
            }
        }

        Double maxSpread = incoming.getMaxSpreadPct();
        if (maxSpread != null) {
            double v = clamp(maxSpread, 0.0, 100.0);
            if (!patchMode || !dblEquals(v, safeD(defaults.getMaxSpreadPct()))) {
                if (!dblEquals(v, safeD(cur.getMaxSpreadPct()))) {
                    cur.setMaxSpreadPct(v);
                    changed = true;
                }
            }
        }

        if (!changed) {
            // ничего не поменяли — не трогаем БД и не шлём event
            return cur;
        }

        WindowScalpingStrategySettings saved = repo.saveAndFlush(cur);

        // ✅ событие строго после коммита, иначе стратегия может прочитать “старое”
        publishAfterCommit(new WindowScalpingSettingsUpdatedEvent(chatId, "update"));

        log.info("✅ WINDOW_SCALPING settings updated (chatId={}, id={}, tpPct={}, slPct={}, windowSize={}, minRangePct={}, entryLowPct={}, entryHighPct={}, maxSpreadPct={})",
                chatId,
                saved.getId(),
                saved.getTakeProfitPct(),
                saved.getStopLossPct(),
                saved.getWindowSize(),
                saved.getMinRangePct(),
                saved.getEntryFromLowPct(),
                saved.getEntryFromHighPct(),
                saved.getMaxSpreadPct()
        );

        return saved;
    }

    /**
     * ✅ Для внутренних нужд (например, reflection-fallback в тюнере).
     * Возвращаем репозиторий напрямую — безопасно, read-only API.
     */
    public WindowScalpingStrategySettingsRepository getRepository() {
        return repo;
    }

    @Override
    public Long getVersion(Long chatId) {
        Integer v = repo.findVersionByChatId(chatId);
        return v != null ? v.longValue() : null;
    }

    // =====================================================
    // helpers
    // =====================================================

    private void publishAfterCommit(Object event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            events.publishEvent(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    events.publishEvent(event);
                } catch (Exception e) {
                    log.warn("⚠️ publishAfterCommit failed: {}", e.toString());
                }
            }
        });
    }

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return min;
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static boolean dblEquals(double a, double b) {
        return Math.abs(a - b) < 1e-12;
    }

    private static double safeD(Double v) {
        return v == null ? 0.0 : v;
    }

    private static boolean bdEquals(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }
}