package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class ScalpingMlGate {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public MlGateResult evaluate(StrategySettings strategySettings,
                                 ScalpingMarketRegimeSnapshot snapshot,
                                 EntryDecision decision) {
        if (strategySettings == null || decision == null) {
            return MlGateResult.pass("ML не используется");
        }

        AdvancedControlMode mode = strategySettings.getAdvancedControlMode() != null
                ? strategySettings.getAdvancedControlMode()
                : AdvancedControlMode.MANUAL;

        if (mode == AdvancedControlMode.MANUAL || !strategySettings.isMlGateEnabled()) {
            return MlGateResult.pass("ML-фильтр отключён, работаю только по правилам стратегии");
        }

        BigDecimal confidence = normalizeProb(strategySettings.getMlConfidenceSafe());
        BigDecimal threshold = normalizeProb(strategySettings.getEffectiveGateMinProbOrNull());

        if (threshold == null || threshold.signum() <= 0) {
            return MlGateResult.pass("ML-фильтр включён без порога, вход разрешён");
        }

        if (confidence == null || confidence.signum() == 0) {
            return MlGateResult.adjust(
                    "Нет свежей уверенности ML, вход не блокирую, но уменьшаю риск",
                    new BigDecimal("0.50"),
                    new BigDecimal("0.95"),
                    new BigDecimal("0.92")
            );
        }

        BigDecimal hardVetoFloor = threshold.multiply(resolveHardVetoMultiplier(snapshot, decision));
        BigDecimal strongAdjustFloor = threshold.multiply(resolveStrongAdjustMultiplier(snapshot, decision));
        BigDecimal lightAdjustFloor = threshold.multiply(resolveLightAdjustMultiplier(snapshot, decision));

        if (confidence.compareTo(hardVetoFloor) < 0) {
            if (canSoftPass(snapshot, decision)) {
                return MlGateResult.adjust(
                        "ML сомневается, но сетап сильный — вхожу сильно уменьшенным объёмом",
                        new BigDecimal("0.35"),
                        new BigDecimal("0.92"),
                        new BigDecimal("0.88")
                );
            }
            return MlGateResult.veto("ML запретил вход: уверенность модели слишком низкая");
        }

        if (confidence.compareTo(strongAdjustFloor) < 0) {
            return MlGateResult.adjust(
                    "ML пропускает вход только с сильным уменьшением риска",
                    new BigDecimal("0.45"),
                    new BigDecimal("0.93"),
                    new BigDecimal("0.89")
            );
        }

        if (confidence.compareTo(lightAdjustFloor) < 0) {
            return MlGateResult.adjust(
                    "ML уменьшил размер позиции и слегка ужесточил риск",
                    new BigDecimal("0.72"),
                    new BigDecimal("0.97"),
                    new BigDecimal("0.95")
            );
        }

        if (snapshot != null
                && snapshot.chaosScore() != null
                && snapshot.chaosScore().compareTo(new BigDecimal("60")) > 0) {
            return MlGateResult.adjust(
                    "ML увидел повышенный риск и уменьшил размер позиции",
                    new BigDecimal("0.82"),
                    new BigDecimal("0.97"),
                    new BigDecimal("0.94")
            );
        }

        return MlGateResult.pass("ML подтвердил вход");
    }

    private boolean canSoftPass(ScalpingMarketRegimeSnapshot snapshot,
                                EntryDecision decision) {
        if (snapshot == null || snapshot.regime() == null) {
            return false;
        }

        boolean allowedRegime =
                snapshot.regime() == ScalpingMarketRegime.TREND_UP
                        || snapshot.regime() == ScalpingMarketRegime.RANGE;

        boolean calmEnough =
                snapshot.chaosScore() == null
                        || snapshot.chaosScore().compareTo(new BigDecimal("30")) <= 0;

        boolean strongDecision =
                decision != null
                        && decision.score() != null
                        && decision.score().compareTo(new BigDecimal("55")) >= 0;

        return allowedRegime && calmEnough && strongDecision;
    }

    private BigDecimal resolveHardVetoMultiplier(ScalpingMarketRegimeSnapshot snapshot,
                                                 EntryDecision decision) {
        if (decision != null && decision.setupType() == ScalpingSetupType.BREAKOUT_CONTINUATION) {
            return new BigDecimal("0.50");
        }

        if (snapshot != null
                && snapshot.regime() != null
                && (snapshot.regime() == ScalpingMarketRegime.TREND_UP || snapshot.regime() == ScalpingMarketRegime.RANGE)
                && snapshot.chaosScore() != null
                && snapshot.chaosScore().compareTo(new BigDecimal("25")) <= 0) {
            return new BigDecimal("0.48");
        }

        return new BigDecimal("0.58");
    }

    private BigDecimal resolveStrongAdjustMultiplier(ScalpingMarketRegimeSnapshot snapshot,
                                                     EntryDecision decision) {
        if (decision != null && decision.setupType() == ScalpingSetupType.BREAKOUT_CONTINUATION) {
            return new BigDecimal("0.78");
        }
        if (snapshot != null && snapshot.regime() == ScalpingMarketRegime.TREND_UP) {
            return new BigDecimal("0.82");
        }
        return new BigDecimal("0.86");
    }

    private BigDecimal resolveLightAdjustMultiplier(ScalpingMarketRegimeSnapshot snapshot,
                                                    EntryDecision decision) {
        if (decision != null
                && decision.score() != null
                && decision.score().compareTo(new BigDecimal("60")) >= 0) {
            return new BigDecimal("0.96");
        }
        if (snapshot != null && snapshot.regime() == ScalpingMarketRegime.RANGE) {
            return new BigDecimal("0.94");
        }
        return ONE;
    }

    private BigDecimal normalizeProb(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            return ZERO;
        }
        if (value.compareTo(ONE) > 0) {
            return ONE;
        }
        return value;
    }

    public record MlGateResult(boolean approved,
                               boolean veto,
                               String reason,
                               BigDecimal riskScaleMultiplier,
                               BigDecimal tpMultiplier,
                               BigDecimal slMultiplier) {
        public static MlGateResult pass(String reason) {
            return new MlGateResult(true, false, reason, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        }

        public static MlGateResult veto(String reason) {
            return new MlGateResult(false, true, reason, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        }

        public static MlGateResult adjust(String reason,
                                          BigDecimal riskScaleMultiplier,
                                          BigDecimal tpMultiplier,
                                          BigDecimal slMultiplier) {
            return new MlGateResult(true, false, reason, riskScaleMultiplier, tpMultiplier, slMultiplier);
        }
    }
}
