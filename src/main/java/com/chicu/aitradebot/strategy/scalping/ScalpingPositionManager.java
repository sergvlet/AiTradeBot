package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.trade.ExitResult;
import com.chicu.aitradebot.trade.PositionStore;
import com.chicu.aitradebot.trade.TradeExecutionService;
import com.chicu.aitradebot.trade.TradeExecutionServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScalpingPositionManager {

    private final PositionStore positionStore;
    private final TradeExecutionService tradeExecutionService;
    private final ObjectProvider<TradeExecutionServiceImpl> executionServiceImplProvider;
    private final StrategyLivePublisher live;

    public void syncFromStore(Long chatId, ScalpingRuntimeState state, boolean pushVisuals) {
        if (chatId == null || state == null || state.getExchange() == null || state.getNetwork() == null || state.getSymbol() == null) {
            return;
        }
        try {
            Optional<PositionStore.PositionSnapshot> opt = positionStore.getPosition(
                    chatId,
                    StrategyType.SCALPING,
                    state.getExchange(),
                    state.getNetwork(),
                    state.getSymbol()
            );
            if (opt.isEmpty()) {
                if (state.isInPosition()) {
                    log.info("[SCALPING] Позиция очищена из PositionStore chatId={} symbol={}", chatId, state.getSymbol());
                    state.resetPositionFlags(Instant.now(), false);
                    live.clearTpSl(chatId, StrategyType.SCALPING, state.getSymbol());
                    live.clearPriceLines(chatId, StrategyType.SCALPING, state.getSymbol());
                }
                return;
            }

            PositionStore.PositionSnapshot snap = opt.get();
            state.setInPosition(snap.qty() != null && snap.qty().signum() > 0);
            state.setLongPosition(state.isInPosition());
            state.setEntryPrice(snap.entryPrice());
            state.setEntryQty(snap.qty());
            state.setTp(snap.tp());
            state.setSl(snap.sl());
            state.setEntryOrderId(snap.entryOrderId());
            state.setEntryOpenedAt(snap.openedAt());

            if (pushVisuals && state.isInPosition()) {
                if (snap.entryPrice() != null) {
                    live.pushPriceLine(chatId, StrategyType.SCALPING, state.getSymbol(), "ENTRY", snap.entryPrice());
                }
                if (snap.tp() != null || snap.sl() != null) {
                    live.pushTpSl(chatId, StrategyType.SCALPING, state.getSymbol(), snap.tp(), snap.sl());
                }
            }
        } catch (Exception e) {
            log.debug("[SCALPING] syncFromStore пропущен chatId={} symbol={} err={}", chatId, state.getSymbol(), e.toString());
        }
    }

    public ManageResult manage(Long chatId,
                               ScalpingRuntimeState state,
                               BigDecimal price,
                               Instant now,
                               ScalpingFeatureSnapshot features,
                               ScalpingMarketRegimeSnapshot snapshot,
                               ScalpingStrategySettings settings) {
        if (chatId == null || state == null || !state.isInPosition() || price == null || price.signum() <= 0) {
            return ManageResult.none();
        }

        Instant time = now != null ? now : Instant.now();

        ExitResult forcedExit = maybeApplyBreakEvenAndForceRules(chatId, state, price, time, snapshot, settings);
        if (forcedExit != null && forcedExit.executed()) {
            afterExit(chatId, state, price, time, forcedExit);
            return ManageResult.exit(forcedExit.reason() != null ? forcedExit.reason() : "forced_exit");
        }

        ExitResult protective = tradeExecutionService.executeExitIfHit(
                chatId,
                StrategyType.SCALPING,
                state.getSymbol(),
                price,
                time,
                true,
                state.getEntryQty(),
                state.getTp(),
                state.getSl(),
                state.getExchange(),
                state.getNetwork()
        );
        if (protective != null && protective.executed()) {
            afterExit(chatId, state, price, time, protective);
            return ManageResult.exit(protective.reason() != null ? protective.reason() : "tp_sl_hit");
        }

        return ManageResult.none();
    }

    public void onEntryOpened(Long chatId,
                              ScalpingRuntimeState state,
                              EntryDecision decision,
                              Instant now) {
        if (chatId == null || state == null) return;
        state.setInPosition(true);
        state.setLongPosition(true);
        state.setBreakEvenApplied(false);
        state.setPartialExitDone(false);
        state.setBreakEvenTriggerPct(decision != null ? decision.breakEvenTriggerPct() : null);
        state.setMaxHoldSec(decision != null ? decision.maxHoldSeconds() : null);
        state.setActiveRiskScale(decision != null ? decision.riskScale() : null);
        state.setLastSetupType(decision != null ? decision.setupType() : null);
        if (now != null) {
            state.setEntryOpenedAt(now);
        }
        if (state.getEntryPrice() != null) {
            live.pushPriceLine(chatId, StrategyType.SCALPING, state.getSymbol(), "ENTRY", state.getEntryPrice());
        }
        live.pushTpSl(chatId, StrategyType.SCALPING, state.getSymbol(), state.getTp(), state.getSl());
    }

    private ExitResult maybeApplyBreakEvenAndForceRules(Long chatId,
                                                        ScalpingRuntimeState state,
                                                        BigDecimal price,
                                                        Instant now,
                                                        ScalpingMarketRegimeSnapshot snapshot,
                                                        ScalpingStrategySettings settings) {
        BigDecimal pnlPct = pnlPct(state.getEntryPrice(), price);
        if (pnlPct == null) {
            return null;
        }

        if (!state.isBreakEvenApplied() && state.getBreakEvenTriggerPct() != null
                && pnlPct.compareTo(state.getBreakEvenTriggerPct()) >= 0
                && state.getEntryPrice() != null) {
            state.setSl(state.getEntryPrice().setScale(8, RoundingMode.HALF_UP));
            state.setBreakEvenApplied(true);
            persistPosition(chatId, state, now);
            log.info("[SCALPING] 🔒 Перевёл стоп в безубыток chatId={} symbol={} entry={} sl={} pnlPct={}",
                    chatId,
                    state.getSymbol(),
                    fmt(state.getEntryPrice()),
                    fmt(state.getSl()),
                    fmt(pnlPct));
            live.pushTpSl(chatId, StrategyType.SCALPING, state.getSymbol(), state.getTp(), state.getSl());
        }

        if (Boolean.TRUE.equals(settings.getPartialExitEnabled())
                && !state.isPartialExitDone()
                && state.getEntryQty() != null
                && state.getEntryQty().signum() > 0
                && settings.getPartialExitPct() != null
                && settings.getPartialExitPct() > 0
                && settings.getPartialExitPct() < 1.0d
                && settings.getPartialExitTriggerPct() != null
                && pnlPct.compareTo(BigDecimal.valueOf(settings.getPartialExitTriggerPct()).setScale(8, RoundingMode.HALF_UP)) >= 0) {
            TradeExecutionServiceImpl impl = executionServiceImplProvider.getIfAvailable();
            if (impl != null) {
                BigDecimal qtyToClose = state.getEntryQty().multiply(BigDecimal.valueOf(settings.getPartialExitPct()))
                        .setScale(8, RoundingMode.DOWN);
                if (qtyToClose.signum() > 0) {
                    ExitResult partial = impl.executeExitNow(
                            chatId,
                            StrategyType.SCALPING,
                            state.getSymbol(),
                            price,
                            now,
                            qtyToClose,
                            state.getTp(),
                            state.getSl(),
                            state.getExchange(),
                            state.getNetwork(),
                            "PARTIAL_EXIT"
                    );
                    if (partial != null && partial.executed()) {
                        state.setPartialExitDone(true);
                        syncFromStore(chatId, state, true);
                        log.info("[SCALPING] ✂️ Частичный выход выполнен chatId={} symbol={} qty={} exitPrice={} reason={}",
                                chatId, state.getSymbol(), fmt(qtyToClose), fmt(partial.exitPrice()), partial.reason());
                        live.pushTrade(chatId, StrategyType.SCALPING, state.getSymbol(), "SELL", partial.exitPrice(), qtyToClose, now);
                    }
                }
            }
        }

        if (state.getEntryOpenedAt() != null && state.getMaxHoldSec() != null && state.getMaxHoldSec() > 0) {
            long hold = Duration.between(state.getEntryOpenedAt(), now).getSeconds();
            if (hold >= state.getMaxHoldSec()) {
                log.info("[SCALPING] ⏱ Time-stop: держим позицию слишком долго chatId={} symbol={} holdSec={} limit={}",
                        chatId, state.getSymbol(), hold, state.getMaxHoldSec());
                return forceExit(chatId, state, price, now, "TIME_STOP");
            }
        }

        if (snapshot != null && settings != null && Boolean.TRUE.equals(settings.getEmergencyChaosExitEnabled())) {
            boolean emergency = snapshot.regime() == ScalpingMarketRegime.CHAOS
                    || snapshot.regime() == ScalpingMarketRegime.NO_TRADE
                    || snapshot.regime() == ScalpingMarketRegime.TREND_DOWN;
            if (emergency) {
                log.info("[SCALPING] 🚨 Early-exit: режим рынка ухудшился chatId={} symbol={} regime={} reason={}",
                        chatId, state.getSymbol(), snapshot.regime(), snapshot.reason());
                return forceExit(chatId, state, price, now, "EARLY_EXIT_" + snapshot.regime().name());
            }
        }

        return null;
    }

    private ExitResult forceExit(Long chatId,
                                 ScalpingRuntimeState state,
                                 BigDecimal price,
                                 Instant now,
                                 String reason) {
        TradeExecutionServiceImpl impl = executionServiceImplProvider.getIfAvailable();
        if (impl == null || state.getEntryQty() == null || state.getEntryQty().signum() <= 0) {
            return null;
        }
        return impl.executeExitNow(
                chatId,
                StrategyType.SCALPING,
                state.getSymbol(),
                price,
                now,
                state.getEntryQty(),
                state.getTp(),
                state.getSl(),
                state.getExchange(),
                state.getNetwork(),
                reason
        );
    }

    private void afterExit(Long chatId,
                           ScalpingRuntimeState state,
                           BigDecimal price,
                           Instant now,
                           ExitResult result) {
        boolean stoppedOut = result != null && (result.slHit() || (result.pnlPercent() != null && result.pnlPercent().signum() < 0));
        live.pushTrade(chatId, StrategyType.SCALPING, state.getSymbol(), "SELL", price, state.getEntryQty(), now);
        live.clearTpSl(chatId, StrategyType.SCALPING, state.getSymbol());
        live.clearPriceLines(chatId, StrategyType.SCALPING, state.getSymbol());
        log.info("[SCALPING] ✅ Выход из позиции chatId={} symbol={} reason={} pnlPct={} stop={}",
                chatId,
                state.getSymbol(),
                result != null ? result.reason() : "UNKNOWN",
                result != null ? fmt(result.pnlPercent()) : "null",
                stoppedOut);
        state.setExits(state.getExits() + 1);
        state.resetPositionFlags(now, stoppedOut);
    }

    private void persistPosition(Long chatId, ScalpingRuntimeState state, Instant now) {
        positionStore.markOpened(
                chatId,
                StrategyType.SCALPING,
                state.getExchange(),
                state.getNetwork(),
                state.getSymbol(),
                state.getEntryPrice(),
                state.getEntryQty(),
                state.getTp(),
                state.getSl(),
                state.getEntryPrice() != null && state.getEntryQty() != null ? state.getEntryPrice().multiply(state.getEntryQty()) : null,
                state.getEntryOrderId(),
                state.getEntryOpenedAt() != null ? state.getEntryOpenedAt() : now
        );
    }

    private static BigDecimal pnlPct(BigDecimal entry, BigDecimal price) {
        if (entry == null || price == null || entry.signum() <= 0 || price.signum() <= 0) return null;
        return price.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(8, RoundingMode.HALF_UP);
    }

    private static String fmt(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    public record ManageResult(boolean exited, String reason) {
        public static ManageResult none() { return new ManageResult(false, "none"); }
        public static ManageResult exit(String reason) { return new ManageResult(true, reason); }
    }
}


