package com.chicu.aitradebot.strategy.windowscalping.ml;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

@Getter
@Setter
public class WindowScalpingMlGateRuntime {

    private boolean initialized;

    private double baseThreshold = 0.30d;
    private double effectiveThreshold = 0.30d;
    private double floorThreshold = 0.18d;
    private double ceilingThreshold = 0.75d;

    private int predictCount;
    private int blockedCount;
    private int approvedCount;

    private int consecutiveBlockedCount;
    private int consecutiveApprovedCount;

    private long lastPredictAt;
    private long lastThresholdDecreaseAt;
    private long lastThresholdIncreaseAt;
    private long lastRetrainAt;

    private long lastHoldLogAt;
    private String lastHoldKey;

    private final Deque<Double> recentApprovedTradePnls = new ArrayDeque<>();

    public void syncFromModel(double base, double floor, double ceiling) {
        double safeFloor = clamp(floor, 0.05d, 0.95d);
        double safeCeiling = clamp(ceiling, safeFloor, 0.95d);
        double safeBase = clamp(base, safeFloor, safeCeiling);

        if (!initialized) {
            this.initialized = true;
            this.floorThreshold = safeFloor;
            this.ceilingThreshold = safeCeiling;
            this.baseThreshold = safeBase;
            this.effectiveThreshold = safeBase;
            return;
        }

        this.floorThreshold = safeFloor;
        this.ceilingThreshold = safeCeiling;
        this.baseThreshold = safeBase;

        this.effectiveThreshold = clamp(this.effectiveThreshold, this.floorThreshold, this.ceilingThreshold);
    }

    public void onPredict(double proba, boolean approved, long nowMs) {
        this.lastPredictAt = nowMs;
        this.predictCount++;

        if (approved) {
            this.approvedCount++;
            this.consecutiveApprovedCount++;
            this.consecutiveBlockedCount = 0;
        } else {
            this.blockedCount++;
            this.consecutiveBlockedCount++;
            this.consecutiveApprovedCount = 0;
        }
    }

    public boolean maybeLowerThreshold(long nowMs,
                                       int minPredicts,
                                       int minBlocked,
                                       long cooldownMs,
                                       double step) {
        if (predictCount < minPredicts) {
            return false;
        }
        if (blockedCount < minBlocked) {
            return false;
        }
        if (consecutiveBlockedCount < Math.max(8, minBlocked / 2)) {
            return false;
        }
        if ((nowMs - lastThresholdDecreaseAt) < cooldownMs) {
            return false;
        }
        if (effectiveThreshold <= floorThreshold + 1e-9) {
            return false;
        }

        double oldValue = effectiveThreshold;
        effectiveThreshold = clamp(effectiveThreshold - step, floorThreshold, ceilingThreshold);
        boolean changed = Math.abs(oldValue - effectiveThreshold) > 1e-9;

        if (changed) {
            lastThresholdDecreaseAt = nowMs;
        }
        return changed;
    }

    public boolean maybeRaiseThresholdFromBadTrades(long nowMs,
                                                    int minTrades,
                                                    double minAvgPnlPct,
                                                    long cooldownMs,
                                                    double step) {
        if (recentApprovedTradePnls.size() < minTrades) {
            return false;
        }
        if ((nowMs - lastThresholdIncreaseAt) < cooldownMs) {
            return false;
        }
        if (effectiveThreshold >= ceilingThreshold - 1e-9) {
            return false;
        }

        double avg = averageRecentApprovedTradePnl();
        if (avg >= minAvgPnlPct) {
            return false;
        }

        double oldValue = effectiveThreshold;
        effectiveThreshold = clamp(effectiveThreshold + step, floorThreshold, ceilingThreshold);
        boolean changed = Math.abs(oldValue - effectiveThreshold) > 1e-9;

        if (changed) {
            lastThresholdIncreaseAt = nowMs;
        }
        return changed;
    }

    public boolean maybeRecoverTowardsBase(long nowMs,
                                           int minTrades,
                                           double minAvgPnlPct,
                                           long cooldownMs,
                                           double step) {
        if (recentApprovedTradePnls.size() < minTrades) {
            return false;
        }
        if ((nowMs - lastThresholdIncreaseAt) < cooldownMs) {
            return false;
        }
        if (effectiveThreshold >= baseThreshold - 1e-9) {
            return false;
        }

        double avg = averageRecentApprovedTradePnl();
        if (avg < minAvgPnlPct) {
            return false;
        }

        double target = Math.min(baseThreshold, effectiveThreshold + step);
        double oldValue = effectiveThreshold;
        effectiveThreshold = clamp(target, floorThreshold, ceilingThreshold);
        boolean changed = Math.abs(oldValue - effectiveThreshold) > 1e-9;

        if (changed) {
            lastThresholdIncreaseAt = nowMs;
        }
        return changed;
    }

    public void recordClosedMlTrade(double pnlPct) {
        recentApprovedTradePnls.addLast(pnlPct);
        while (recentApprovedTradePnls.size() > 20) {
            recentApprovedTradePnls.removeFirst();
        }
    }

    public double averageRecentApprovedTradePnl() {
        if (recentApprovedTradePnls.isEmpty()) {
            return 0.0d;
        }

        double sum = 0.0d;
        for (Double value : recentApprovedTradePnls) {
            if (value != null) {
                sum += value;
            }
        }
        return sum / recentApprovedTradePnls.size();
    }

    public boolean shouldRequestRetrain(long nowMs,
                                        long cooldownMs,
                                        int minPredicts,
                                        int minBlocked) {
        if (predictCount < minPredicts) {
            return false;
        }
        if (blockedCount < minBlocked) {
            return false;
        }
        if ((nowMs - lastRetrainAt) < cooldownMs) {
            return false;
        }

        double blockedRatio = predictCount == 0 ? 0.0d : ((double) blockedCount / (double) predictCount);
        boolean thresholdOnFloor = effectiveThreshold <= floorThreshold + 1e-9;

        if (blockedRatio < 0.85d && !thresholdOnFloor) {
            return false;
        }

        lastRetrainAt = nowMs;
        return true;
    }

    public boolean shouldLogHold(double proba, long nowMs, long cooldownMs) {
        String key = String.format(
                Locale.US,
                "p=%.3f|t=%.3f",
                round3(proba),
                round3(effectiveThreshold)
        );

        boolean changed = lastHoldKey == null || !lastHoldKey.equals(key);
        boolean cooledDown = (nowMs - lastHoldLogAt) >= cooldownMs;

        if (changed || cooledDown) {
            lastHoldKey = key;
            lastHoldLogAt = nowMs;
            return true;
        }
        return false;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}