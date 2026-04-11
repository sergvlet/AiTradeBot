package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.trade.TradeExecutionServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class ScalpingExecutionGuard {

    private final ObjectProvider<TradeExecutionServiceImpl> executionServiceProvider;

    public GuardResult validate(Long chatId,
                                ScalpingRuntimeState state,
                                StrategySettings strategySettings,
                                ScalpingStrategySettings settings,
                                ScalpingFeatureSnapshot features,
                                ScalpingMarketRegimeSnapshot snapshot,
                                EntryDecision decision,
                                Instant now) {

        if (state == null || settings == null || features == null || snapshot == null) {
            return GuardResult.block("no_state", "нет runtime state или market snapshot");
        }
        if (decision == null || !decision.allowed()) {
            return GuardResult.block("decision_blocked", decision != null ? decision.reason() : "entry decision пустой");
        }
        if (state.isInPosition()) {
            return GuardResult.block("already_in_position", "позиция уже открыта");
        }

        if (state.getLastStopAt() != null) {
            long afterStop = Math.max(5L, settings.getCooldownAfterStopSec() != null ? settings.getCooldownAfterStopSec() : 40);
            if (Duration.between(state.getLastStopAt(), now).getSeconds() < afterStop) {
                return GuardResult.block("cooldown_after_stop", "после стопа ещё действует защитный кулдаун");
            }
        }

        if (state.getLastExitAt() != null) {
            long afterExit = Math.max(3L, settings.getCooldownAfterExitSec() != null ? settings.getCooldownAfterExitSec() : 14);
            if (Duration.between(state.getLastExitAt(), now).getSeconds() < afterExit) {
                return GuardResult.block("cooldown_after_exit", "слишком рано после последнего выхода");
            }
        }

        int maxStops = settings.getMaxConsecutiveStops() != null ? settings.getMaxConsecutiveStops() : 3;
        if (state.getConsecutiveStops() >= maxStops) {
            return GuardResult.block("max_consecutive_stops", "серия стопов ещё не отработана");
        }

        if (snapshot.regime() == ScalpingMarketRegime.NO_TRADE) {
            return GuardResult.block("regime_block", "режим рынка запрещает вход");
        }
        if (snapshot.regime() == ScalpingMarketRegime.CHAOS && !allowChaosBreakout(settings, features, snapshot, decision)) {
            return GuardResult.block("regime_block", "режим рынка запрещает вход");
        }
        if (snapshot.regime() == ScalpingMarketRegime.TREND_DOWN && !Boolean.TRUE.equals(settings.getAllowCounterTrendTrades())) {
            return GuardResult.block("trend_down_spot_block", "для спота нисходящий режим заблокирован");
        }

        double maxSpread = firstPositive(settings.getMaxSpreadPct(), settings.getSpreadLimitPct(), 0.12d);
        if (features.spreadPct() != null && features.spreadPct().doubleValue() > maxSpread) {
            return GuardResult.block("spread_bad", "спред выше допустимого");
        }

        double minAtr = firstPositive(settings.getMinAtrPct(), 0.03d);
        if (features.atrPct() != null && features.atrPct().doubleValue() < minAtr) {
            return GuardResult.block("atr_too_small", "волатильность слишком маленькая");
        }

        double maxAtr = firstPositive(settings.getMaxAtrPct(), settings.getAtrPctRange(), 0.80d);
        if (features.atrPct() != null && features.atrPct().doubleValue() > maxAtr) {
            return GuardResult.block("atr_too_large", "волатильность слишком высокая");
        }

        double minVolumeRatio = firstPositive(settings.getMinVolumeRatio(), settings.getVolumeRatio(), 0.70d);
        if (!features.volumeProxy() && features.volumeRatio() != null && features.volumeRatio().doubleValue() < minVolumeRatio) {
            return GuardResult.block("volume_low", "объём не подтверждает вход");
        }

        if (features.wickBodyRatio() != null && features.wickBodyRatio().doubleValue() > 4.50d) {
            return GuardResult.block("anomaly_candle", "аномальная свеча с плохим wick/body");
        }

        double rrMin = firstPositive(settings.getMinRiskReward(), settings.getRiskRewardMin(), 1.05d);
        if (features.riskRewardRatio() != null && features.riskRewardRatio().doubleValue() < rrMin) {
            return GuardResult.block("risk_reward_low", "отношение риск/прибыль слишком слабое");
        }

        TradeExecutionServiceImpl execution = executionServiceProvider.getIfAvailable();
        if (execution != null && chatId != null && strategySettings != null && state.getLastPrice() != null) {
            TradeExecutionServiceImpl.EntryPrecheckResult precheck = execution.precheckEntryFast(
                    chatId,
                    StrategyType.SCALPING,
                    state.getSymbol(),
                    state.getLastPrice(),
                    strategySettings,
                    now
            );
            if (precheck != null && !precheck.allowed()) {
                return GuardResult.block(precheck.code(), precheck.details());
            }
        }

        return GuardResult.allow();
    }

    private boolean allowChaosBreakout(ScalpingStrategySettings settings,
                                       ScalpingFeatureSnapshot features,
                                       ScalpingMarketRegimeSnapshot snapshot,
                                       EntryDecision decision) {
        if (decision == null || decision.setupType() != ScalpingSetupType.BREAKOUT_CONTINUATION) {
            return false;
        }

        double trendMin = firstPositive(settings.getTrendMinScore(), 58.0d);
        double chaosThreshold = firstPositive(settings.getChaosBlockThreshold(), 62.0d);
        double trendScore = snapshot.trendScore() != null ? snapshot.trendScore().doubleValue() : 0.0d;
        double chaosScore = snapshot.chaosScore() != null ? snapshot.chaosScore().doubleValue() : 100.0d;
        double breakoutPressure = features.breakoutPressure() != null ? features.breakoutPressure().doubleValue() : 0.0d;

        return trendScore >= (trendMin + 8.0d)
                && chaosScore <= (chaosThreshold + 12.0d)
                && breakoutPressure >= 1.40d
                && gt(features.emaFast(), features.emaSlow());
    }

    private static boolean gt(java.math.BigDecimal a, java.math.BigDecimal b) {
        return a != null && b != null && a.compareTo(b) > 0;
    }

    private static double firstPositive(Double primary, Double secondary, double fallback) {
        if (primary != null && primary > 0) return primary;
        if (secondary != null && secondary > 0) return secondary;
        return fallback;
    }

    private static double firstPositive(Double primary, double fallback) {
        if (primary != null && primary > 0) return primary;
        return fallback;
    }

    public record GuardResult(boolean allowed, String code, String message) {
        public static GuardResult allow() { return new GuardResult(true, "ok", "ok"); }
        public static GuardResult block(String code, String message) { return new GuardResult(false, code, message); }
    }
}

