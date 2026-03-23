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
import java.math.RoundingMode;
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
            return repo.findByChatId(chatId).orElseThrow(() -> dup);
        }
    }

    @Override
    @Transactional
    public WindowScalpingStrategySettings update(Long chatId, WindowScalpingStrategySettings incoming) {
        if (chatId == null || chatId <= 0) throw new IllegalArgumentException("chatId must be positive");
        if (incoming == null) throw new IllegalArgumentException("incoming is null");

        WindowScalpingStrategySettings cur = getOrCreate(chatId);
        boolean patchMode = (incoming.getId() == null);

        WindowScalpingStrategySettings defaults = WindowScalpingStrategySettings.builder()
                .chatId(chatId)
                .build();

        boolean changed = false;

        changed |= applyBigDecimal(incoming.getTakeProfitPct(), defaults.getTakeProfitPct(), cur.getTakeProfitPct(), patchMode, cur::setTakeProfitPct);
        changed |= applyBigDecimal(incoming.getStopLossPct(), defaults.getStopLossPct(), cur.getStopLossPct(), patchMode, cur::setStopLossPct);

        changed |= applyBoolean(incoming.getAutoTpSlEnabled(), defaults.getAutoTpSlEnabled(), cur.getAutoTpSlEnabled(), patchMode, cur::setAutoTpSlEnabled);
        changed |= applyBigDecimal(incoming.getAutoSlFromRangeFactor(), defaults.getAutoSlFromRangeFactor(), cur.getAutoSlFromRangeFactor(), patchMode, cur::setAutoSlFromRangeFactor);
        changed |= applyBigDecimal(incoming.getAutoTpFromRangeFactor(), defaults.getAutoTpFromRangeFactor(), cur.getAutoTpFromRangeFactor(), patchMode, cur::setAutoTpFromRangeFactor);
        changed |= applyBigDecimal(incoming.getAutoMinRiskReward(), defaults.getAutoMinRiskReward(), cur.getAutoMinRiskReward(), patchMode, cur::setAutoMinRiskReward);
        changed |= applyBigDecimal(incoming.getAutoSlMinPct(), defaults.getAutoSlMinPct(), cur.getAutoSlMinPct(), patchMode, cur::setAutoSlMinPct);
        changed |= applyBigDecimal(incoming.getAutoSlMaxPct(), defaults.getAutoSlMaxPct(), cur.getAutoSlMaxPct(), patchMode, cur::setAutoSlMaxPct);
        changed |= applyBigDecimal(incoming.getAutoTpMinPct(), defaults.getAutoTpMinPct(), cur.getAutoTpMinPct(), patchMode, cur::setAutoTpMinPct);
        changed |= applyBigDecimal(incoming.getAutoTpMaxPct(), defaults.getAutoTpMaxPct(), cur.getAutoTpMaxPct(), patchMode, cur::setAutoTpMaxPct);
        changed |= applyBigDecimal(incoming.getAutoTpMlBoostFactor(), defaults.getAutoTpMlBoostFactor(), cur.getAutoTpMlBoostFactor(), patchMode, cur::setAutoTpMlBoostFactor);
        changed |= applyBigDecimal(incoming.getAutoTpWeakSignalFactor(), defaults.getAutoTpWeakSignalFactor(), cur.getAutoTpWeakSignalFactor(), patchMode, cur::setAutoTpWeakSignalFactor);

        Integer ws = incoming.getWindowSize();
        if (ws != null && ws >= 5) {
            if (!patchMode || !Objects.equals(ws, defaults.getWindowSize())) {
                if (!Objects.equals(ws, cur.getWindowSize())) {
                    cur.setWindowSize(ws);
                    changed = true;
                }
            }
        }

        changed |= applyDouble(incoming.getEntryFromLowPct(), defaults.getEntryFromLowPct(), cur.getEntryFromLowPct(), patchMode, 0.0, 100.0, cur::setEntryFromLowPct);
        changed |= applyDouble(incoming.getEntryFromHighPct(), defaults.getEntryFromHighPct(), cur.getEntryFromHighPct(), patchMode, 0.0, 100.0, cur::setEntryFromHighPct);
        changed |= applyDouble(incoming.getMinRangePct(), defaults.getMinRangePct(), cur.getMinRangePct(), patchMode, 0.0, 100.0, cur::setMinRangePct);
        changed |= applyDouble(incoming.getMaxSpreadPct(), defaults.getMaxSpreadPct(), cur.getMaxSpreadPct(), patchMode, 0.0, 100.0, cur::setMaxSpreadPct);

        normalizeAutoBounds(cur);

        if (!changed) {
            return cur;
        }

        WindowScalpingStrategySettings saved = repo.saveAndFlush(cur);
        publishAfterCommit(new WindowScalpingSettingsUpdatedEvent(chatId, "update"));

        log.info("✅ WINDOW_SCALPING settings updated (chatId={}, id={}, tpPct={}, slPct={}, autoTpSl={}, slFactor={}, tpFactor={}, minRR={}, slMinPct={}, slMaxPct={}, tpMinPct={}, tpMaxPct={}, tpMlBoost={}, tpWeakFactor={}, windowSize={}, minRangePct={}, entryLowPct={}, entryHighPct={}, maxSpreadPct={})",
                chatId,
                saved.getId(),
                saved.getTakeProfitPct(),
                saved.getStopLossPct(),
                saved.getAutoTpSlEnabled(),
                saved.getAutoSlFromRangeFactor(),
                saved.getAutoTpFromRangeFactor(),
                saved.getAutoMinRiskReward(),
                saved.getAutoSlMinPct(),
                saved.getAutoSlMaxPct(),
                saved.getAutoTpMinPct(),
                saved.getAutoTpMaxPct(),
                saved.getAutoTpMlBoostFactor(),
                saved.getAutoTpWeakSignalFactor(),
                saved.getWindowSize(),
                saved.getMinRangePct(),
                saved.getEntryFromLowPct(),
                saved.getEntryFromHighPct(),
                saved.getMaxSpreadPct()
        );

        return saved;
    }

    public WindowScalpingStrategySettingsRepository getRepository() {
        return repo;
    }

    @Override
    public Long getVersion(Long chatId) {
        Integer v = repo.findVersionByChatId(chatId);
        return v != null ? v.longValue() : null;
    }

    private void normalizeAutoBounds(WindowScalpingStrategySettings cur) {
        if (cur == null) return;

        BigDecimal slMin = positiveOrDefault(cur.getAutoSlMinPct(), new BigDecimal("0.04"));
        BigDecimal slMax = positiveOrDefault(cur.getAutoSlMaxPct(), new BigDecimal("0.18"));
        if (slMax.compareTo(slMin) < 0) slMax = slMin;

        BigDecimal tpMin = positiveOrDefault(cur.getAutoTpMinPct(), new BigDecimal("0.10"));
        BigDecimal tpMax = positiveOrDefault(cur.getAutoTpMaxPct(), new BigDecimal("0.80"));
        if (tpMax.compareTo(tpMin) < 0) tpMax = tpMin;

        BigDecimal minRr = positiveOrDefault(cur.getAutoMinRiskReward(), new BigDecimal("2.40"));
        BigDecimal minTpByRr = slMin.multiply(minRr);
        if (tpMin.compareTo(minTpByRr) < 0) {
            tpMin = minTpByRr.setScale(8, RoundingMode.HALF_UP);
            if (tpMax.compareTo(tpMin) < 0) tpMax = tpMin;
        }

        cur.setAutoSlMinPct(slMin.setScale(8, RoundingMode.HALF_UP));
        cur.setAutoSlMaxPct(slMax.setScale(8, RoundingMode.HALF_UP));
        cur.setAutoTpMinPct(tpMin.setScale(8, RoundingMode.HALF_UP));
        cur.setAutoTpMaxPct(tpMax.setScale(8, RoundingMode.HALF_UP));
    }

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

    private boolean applyBigDecimal(BigDecimal incoming,
                                    BigDecimal defaultValue,
                                    BigDecimal currentValue,
                                    boolean patchMode,
                                    java.util.function.Consumer<BigDecimal> setter) {
        if (incoming == null || incoming.signum() <= 0) return false;
        if (patchMode && bdEquals(incoming, defaultValue)) return false;
        if (bdEquals(incoming, currentValue)) return false;
        setter.accept(incoming.setScale(8, RoundingMode.HALF_UP));
        return true;
    }

    private boolean applyBoolean(Boolean incoming,
                                 Boolean defaultValue,
                                 Boolean currentValue,
                                 boolean patchMode,
                                 java.util.function.Consumer<Boolean> setter) {
        if (incoming == null) return false;
        if (patchMode && Objects.equals(incoming, defaultValue)) return false;
        if (Objects.equals(incoming, currentValue)) return false;
        setter.accept(incoming);
        return true;
    }

    private boolean applyDouble(Double incoming,
                                Double defaultValue,
                                Double currentValue,
                                boolean patchMode,
                                double min,
                                double max,
                                java.util.function.Consumer<Double> setter) {
        if (incoming == null) return false;
        double v = clamp(incoming, min, max);
        if (patchMode && dblEquals(v, safeD(defaultValue))) return false;
        if (dblEquals(v, safeD(currentValue))) return false;
        setter.accept(v);
        return true;
    }

    private static BigDecimal positiveOrDefault(BigDecimal v, BigDecimal def) {
        return (v != null && v.signum() > 0) ? v : def;
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
