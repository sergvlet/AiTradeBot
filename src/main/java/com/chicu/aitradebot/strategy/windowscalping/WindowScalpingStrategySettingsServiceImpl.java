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
            WindowScalpingStrategySettings again = repo.findByChatId(chatId)
                    .orElseThrow(() -> dup);
            return again;
        }
    }

    @Override
    @Transactional
    public WindowScalpingStrategySettings update(Long chatId, WindowScalpingStrategySettings incoming) {
        if (chatId == null || chatId <= 0) throw new IllegalArgumentException("chatId must be positive");
        if (incoming == null) throw new IllegalArgumentException("incoming is null");

        WindowScalpingStrategySettings cur = getOrCreate(chatId);

        // ✅ TP/SL
        BigDecimal tp = incoming.getTakeProfitPct();
        if (tp != null && tp.signum() > 0) cur.setTakeProfitPct(tp);

        BigDecimal sl = incoming.getStopLossPct();
        if (sl != null && sl.signum() > 0) cur.setStopLossPct(sl);

        // ✅ WINDOW поля
        Integer ws = incoming.getWindowSize();
        if (ws != null && ws >= 5) cur.setWindowSize(ws);

        Double low = incoming.getEntryFromLowPct();
        if (low != null) cur.setEntryFromLowPct(clamp(low, 0.0, 100.0));

        Double high = incoming.getEntryFromHighPct();
        if (high != null) cur.setEntryFromHighPct(clamp(high, 0.0, 100.0));

        Double minRange = incoming.getMinRangePct();
        if (minRange != null) cur.setMinRangePct(clamp(minRange, 0.0, 100.0));

        Double maxSpread = incoming.getMaxSpreadPct();
        if (maxSpread != null) cur.setMaxSpreadPct(clamp(maxSpread, 0.0, 100.0));

        WindowScalpingStrategySettings saved = repo.saveAndFlush(cur);

        // ✅ событие строго после коммита, иначе стратегия может прочитать “старое”
        publishAfterCommit(new WindowScalpingSettingsUpdatedEvent(chatId, "update"));

        log.info("✅ WINDOW_SCALPING settings updated (chatId={}, id={}, tpPct={}, slPct={}, windowSize={}, minRangePct={})",
                chatId,
                saved.getId(),
                saved.getTakeProfitPct(),
                saved.getStopLossPct(),
                saved.getWindowSize(),
                saved.getMinRangePct()
        );

        return saved;
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
}
