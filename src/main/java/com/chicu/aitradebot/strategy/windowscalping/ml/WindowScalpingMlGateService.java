package com.chicu.aitradebot.strategy.windowscalping.ml;

import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class WindowScalpingMlGateService {

    private static final double DEFAULT_BASE_THRESHOLD = 0.30d;
    private static final double DEFAULT_FLOOR_THRESHOLD = 0.18d;
    private static final double DEFAULT_CEILING_THRESHOLD = 0.75d;

    private static final int MIN_PREDICTS_BEFORE_LOWER = 40;
    private static final int MIN_BLOCKS_BEFORE_LOWER = 24;
    private static final int MIN_PREDICTS_BEFORE_RETRAIN = 120;
    private static final int MIN_BLOCKS_BEFORE_RETRAIN = 100;

    private static final int MIN_TRADES_BEFORE_RAISE = 6;
    private static final int MIN_TRADES_BEFORE_RECOVER = 4;

    private static final long HOLD_LOG_COOLDOWN_MS = 10_000L;
    private static final long LOWER_COOLDOWN_MS = 10 * 60_000L;
    private static final long RAISE_COOLDOWN_MS = 10 * 60_000L;
    private static final long RETRAIN_COOLDOWN_MS = 30 * 60_000L;

    private static final double LOWER_STEP = 0.02d;
    private static final double RAISE_STEP = 0.02d;
    private static final double RECOVER_STEP = 0.01d;

    private static final double BAD_TRADES_AVG_PNL_THRESHOLD = 0.0d;
    private static final double GOOD_TRADES_AVG_PNL_THRESHOLD = 0.15d;

    private final ConcurrentHashMap<Long, WindowScalpingMlGateRuntime> runtimes = new ConcurrentHashMap<>();

    public WindowScalpingMlGateDecision evaluate(long chatId,
                                                 String symbol,
                                                 String timeframe,
                                                 Double fallbackThreshold,
                                                 MlPredictResponse response) {
        long nowMs = System.currentTimeMillis();

        WindowScalpingMlGateRuntime runtime = runtimes.computeIfAbsent(chatId, id -> new WindowScalpingMlGateRuntime());

        double fallback = clamp(
                fallbackThreshold == null ? DEFAULT_BASE_THRESHOLD : fallbackThreshold,
                DEFAULT_FLOOR_THRESHOLD,
                DEFAULT_CEILING_THRESHOLD
        );

        double baseThreshold = response == null
                ? fallback
                : response.recommendedThresholdOr(fallback);

        double floorThreshold = response == null
                ? DEFAULT_FLOOR_THRESHOLD
                : response.floorThresholdOr(DEFAULT_FLOOR_THRESHOLD);

        double ceilingThreshold = response == null
                ? DEFAULT_CEILING_THRESHOLD
                : response.ceilingThresholdOr(DEFAULT_CEILING_THRESHOLD);

        runtime.syncFromModel(baseThreshold, floorThreshold, ceilingThreshold);

        double previousThreshold = runtime.getEffectiveThreshold();

        // Сначала пробуем мягко ослабить порог по уже накопленной истории.
        boolean adjustedBeforePredict = runtime.maybeLowerThreshold(
                nowMs,
                MIN_PREDICTS_BEFORE_LOWER,
                MIN_BLOCKS_BEFORE_LOWER,
                LOWER_COOLDOWN_MS,
                LOWER_STEP
        );

        double probability = response == null ? 0.0d : response.probabilityOrZero();
        double activeThreshold = runtime.getEffectiveThreshold();

        boolean approved = probability >= activeThreshold;
        runtime.onPredict(probability, approved, nowMs);

        // Если именно этим прогнозом мы только что добили streak блокировок —
        // снижаем порог уже на следующий тик.
        boolean adjustedAfterPredict = false;
        if (!approved && !adjustedBeforePredict) {
            adjustedAfterPredict = runtime.maybeLowerThreshold(
                    nowMs,
                    MIN_PREDICTS_BEFORE_LOWER,
                    MIN_BLOCKS_BEFORE_LOWER,
                    LOWER_COOLDOWN_MS,
                    LOWER_STEP
            );
        }

        boolean thresholdAdjusted = adjustedBeforePredict || adjustedAfterPredict;
        double newThreshold = runtime.getEffectiveThreshold();
        boolean shouldLog = !approved && runtime.shouldLogHold(probability, nowMs, HOLD_LOG_COOLDOWN_MS);

        boolean shouldRequestRetrain = !approved && runtime.shouldRequestRetrain(
                nowMs,
                RETRAIN_COOLDOWN_MS,
                MIN_PREDICTS_BEFORE_RETRAIN,
                MIN_BLOCKS_BEFORE_RETRAIN
        );

        String reason;
        if (approved) {
            reason = "approved";
        } else if (thresholdAdjusted) {
            reason = "below_threshold_auto_adjusted";
        } else {
            reason = "below_threshold";
        }

        return WindowScalpingMlGateDecision.builder()
                .approved(approved)
                .probability(probability)
                .threshold(activeThreshold)
                .baseThreshold(runtime.getBaseThreshold())
                .floorThreshold(runtime.getFloorThreshold())
                .ceilingThreshold(runtime.getCeilingThreshold())
                .shouldLog(shouldLog)
                .thresholdAdjusted(thresholdAdjusted)
                .previousThreshold(previousThreshold)
                .newThreshold(newThreshold)
                .shouldRequestRetrain(shouldRequestRetrain)
                .reason(reason)
                .build();
    }

    /**
     * Вызывать из стратегии, когда закрылась именно ML-одобренная сделка.
     * Возвращает текст для лога, если порог был изменён.
     */
    public String onMlTradeClosed(long chatId, String symbol, String timeframe, double realizedPnlPct) {
        WindowScalpingMlGateRuntime runtime = runtimes.get(chatId);
        if (runtime == null) {
            return null;
        }

        long nowMs = System.currentTimeMillis();
        runtime.recordClosedMlTrade(realizedPnlPct);

        double before = runtime.getEffectiveThreshold();

        boolean raisedBecauseBadTrades = runtime.maybeRaiseThresholdFromBadTrades(
                nowMs,
                MIN_TRADES_BEFORE_RAISE,
                BAD_TRADES_AVG_PNL_THRESHOLD,
                RAISE_COOLDOWN_MS,
                RAISE_STEP
        );

        boolean recoveredTowardsBase = false;
        if (!raisedBecauseBadTrades) {
            recoveredTowardsBase = runtime.maybeRecoverTowardsBase(
                    nowMs,
                    MIN_TRADES_BEFORE_RECOVER,
                    GOOD_TRADES_AVG_PNL_THRESHOLD,
                    RAISE_COOLDOWN_MS,
                    RECOVER_STEP
            );
        }

        double after = runtime.getEffectiveThreshold();

        if (raisedBecauseBadTrades && Math.abs(before - after) > 1e-9) {
            return String.format(
                    "[WINDOW] 🤖 ADAPTIVE GATE UP chatId=%d sym=%s tf=%s %.4f -> %.4f avgClosedMlPnl=%.4f",
                    chatId,
                    symbol,
                    timeframe,
                    before,
                    after,
                    runtime.averageRecentApprovedTradePnl()
            );
        }

        if (recoveredTowardsBase && Math.abs(before - after) > 1e-9) {
            return String.format(
                    "[WINDOW] 🤖 ADAPTIVE GATE RECOVER chatId=%d sym=%s tf=%s %.4f -> %.4f avgClosedMlPnl=%.4f base=%.4f",
                    chatId,
                    symbol,
                    timeframe,
                    before,
                    after,
                    runtime.averageRecentApprovedTradePnl(),
                    runtime.getBaseThreshold()
            );
        }

        return null;
    }

    public void reset(long chatId) {
        runtimes.remove(chatId);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}