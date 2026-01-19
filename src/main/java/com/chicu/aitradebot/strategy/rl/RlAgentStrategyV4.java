// src/main/java/com/chicu/aitradebot/strategy/rl/RlAgentStrategyV4.java
package com.chicu.aitradebot.strategy.rl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.trade.TradeExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RL_AGENT Strategy (V4)
 *
 * Агент принимает действие: BUY / SELL / HOLD + confidence [0..1]
 * - BUY -> executeEntry
 * - SELL -> НЕ закрываем по рынку, а только сигналим; выход по TP/SL
 * - EXIT делаем через executeExitIfHit
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.RL_AGENT)
public class RlAgentStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 300;

    private final StrategyLivePublisher live;
    private final RlAgentSettingsService rlSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final CandleProvider candleProvider;

    /** Тонкий интерфейс агента — подключишь свою реализацию */
    private final RlAgentService rlAgentService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        RlAgentSettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        String lastFingerprint;

        long ticks;

        boolean inPosition;
        BigDecimal entryQty;
        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;

        String lastHoldReason;
        Instant lastHoldAt;
    }

    @Override
    public void start(Long chatId, String ignored) {
        StrategySettings ss = loadStrategySettings(chatId);
        RlAgentSettings cfg = rlSettingsService.getOrCreate(chatId);

        LocalState st = new LocalState();
        st.active = true;
        st.startedAt = Instant.now();
        st.ss = ss;
        st.cfg = cfg;

        st.symbol = safeUpper(ss.getSymbol());
        st.exchange = ss.getExchangeName();
        st.network = ss.getNetworkType();

        st.lastSettingsLoadAt = Instant.now();
        st.lastFingerprint = buildFingerprint(ss, cfg);

        states.put(chatId, st);

        int lookback = resolveLookback(ss, cfg);
        log.info("[RL_AGENT] ▶ START chatId={} symbol={} lookback={} minConf={}",
                chatId, st.symbol, lookback, fmtBd(cfg.getMinConfidence()));

        safeLive(() -> live.pushState(chatId, StrategyType.RL_AGENT, st.symbol, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.RL_AGENT, st.symbol, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {
        LocalState st = states.remove(chatId);
        if (st == null) return;

        String sym = st.symbol;
        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.RL_AGENT, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.RL_AGENT, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.RL_AGENT, sym, false));
        }

        log.info("[RL_AGENT] ⏹ STOP chatId={} symbol={} ticks={} inPos={}",
                chatId, sym, st.ticks, st.inPosition);
    }

    @Override
    public boolean isActive(Long chatId) {
        LocalState st = states.get(chatId);
        return st != null && st.active;
    }

    @Override
    public Instant getStartedAt(Long chatId) {
        LocalState st = states.get(chatId);
        return st != null ? st.startedAt : null;
    }

    @Override
    public void onPriceUpdate(Long chatId, String symbolFromTick, BigDecimal price, Instant ts) {

        LocalState st = states.get(chatId);
        if (st == null || !st.active) return;

        st.ticks++;

        if (price == null || price.signum() <= 0) return;
        Instant time = (ts != null ? ts : Instant.now());

        String tickSym = safeUpper(symbolFromTick);
        String cfgSym = safeUpper(st.symbol);
        if (cfgSym != null && tickSym != null && !cfgSym.equals(tickSym)) return;
        if (cfgSym == null && tickSym != null) st.symbol = tickSym;

        final String symFinal = safeUpper(st.symbol);
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.RL_AGENT, symFinal, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            final StrategySettings ss = st.ss;
            final RlAgentSettings cfg = st.cfg;

            if (symFinal == null) {
                pushHoldThrottled(chatId, null, st, "no_symbol", time);
                return;
            }
            if (cfg == null) {
                pushHoldThrottled(chatId, symFinal, st, "no_rl_settings", time);
                return;
            }
            if (!cfg.isEnabled()) {
                pushHoldThrottled(chatId, symFinal, st, "rl_disabled", time);
                return;
            }
            if (ss == null || ss.getTimeframe() == null || ss.getTimeframe().trim().isEmpty()) {
                pushHoldThrottled(chatId, symFinal, st, "no_timeframe", time);
                return;
            }

            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[RL_AGENT] tick chatId={} sym={} price={} inPos={}",
                        chatId, symFinal, fmtBd(price), st.inPosition);
            }

            // 1) EXIT TP/SL
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.RL_AGENT,
                            symFinal,
                            price,
                            time,
                            false,
                            st.entryQty,
                            st.tp,
                            st.sl
                    );

                    if (ex.executed()) {
                        clearPosition(st);

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.RL_AGENT, symFinal));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.RL_AGENT, symFinal));
                        safeLive(() -> live.pushSignal(chatId, StrategyType.RL_AGENT, symFinal, null, Signal.sell(1.0, "tp_sl_exit")));
                        return;
                    }
                } catch (Exception e) {
                    log.error("[RL_AGENT] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
                }
            }

            // 2) решение агента
            int lookback = resolveLookback(ss, cfg);

            var candles = candleProvider.getRecentCandles(chatId, symFinal, ss.getTimeframe(), lookback);
            if (candles == null || candles.size() < Math.min(30, Math.max(20, lookback / 2))) {
                pushHoldThrottled(chatId, symFinal, st, "not_enough_candles", time);
                return;
            }

            RlState obs = RlState.fromCandles(candles, price);

            RlDecision dec;
            try {
                dec = rlAgentService.decide(chatId, symFinal, ss.getTimeframe(), obs);
            } catch (Exception e) {
                log.warn("[RL_AGENT] ⚠ decide failed chatId={} sym={} err={}", chatId, symFinal, e.toString());
                pushHoldThrottled(chatId, symFinal, st, "decide_failed", time);
                return;
            }

            if (dec == null) {
                pushHoldThrottled(chatId, symFinal, st, "decision_null", time);
                return;
            }

            final double minConf = normalizeThreshold(cfg.getMinConfidence());

            // ✅ FIX: было decision.confidence() (переменной нет). Должно быть dec.confidence()
            // ✅ FIX: clamp01 умеет BigDecimal
            final double conf = clamp01(dec.confidence());

            final RlAction action = (dec.action() != null ? dec.action() : RlAction.HOLD);

            if (conf < minConf) {
                pushHoldThrottled(chatId, symFinal, st, "low_conf " + round2(conf), time);
                return;
            }

            // BUY только если нет позиции
            if (!st.inPosition && action == RlAction.BUY) {

                double score = Math.min(100.0, Math.max(50.0, conf * 100.0));
                final double scoreFinal = score;

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.RL_AGENT,
                            symFinal,
                            price,
                            BigDecimal.valueOf(scoreFinal / 100.0),
                            time,
                            ss
                    );

                    if (!res.executed()) {
                        pushHoldThrottled(chatId, symFinal, st, res.reason(), time);
                        return;
                    }

                    st.inPosition = true;
                    st.entryPrice = res.entryPrice();
                    st.entryQty = res.qty();
                    st.tp = res.tp();
                    st.sl = res.sl();

                    safeLive(() -> live.pushSignal(chatId, StrategyType.RL_AGENT, symFinal, null,
                            Signal.buy(scoreFinal, "rl_buy conf=" + round2(conf))));
                    return;

                } catch (Exception e) {
                    log.error("[RL_AGENT] ❌ BUY failed chatId={} err={}", chatId, e.getMessage(), e);
                    pushHoldThrottled(chatId, symFinal, st, "buy_failed", time);
                    return;
                }
            }

            // SELL — только сигналим (выход по TP/SL)
            if (st.inPosition && action == RlAction.SELL) {
                safeLive(() -> live.pushSignal(chatId, StrategyType.RL_AGENT, symFinal, null,
                        Signal.hold("rl_sell_signal conf=" + round2(conf))));
                return;
            }

            pushHoldThrottled(chatId, symFinal, st, "hold " + action.name().toLowerCase(), time);
        }
    }

    private void clearPosition(LocalState st) {
        st.inPosition = false;
        st.entryQty = null;
        st.entryPrice = null;
        st.tp = null;
        st.sl = null;
    }

    private void refreshSettingsIfNeeded(Long chatId, LocalState st, Instant now) {
        if (st.lastSettingsLoadAt != null &&
            Duration.between(st.lastSettingsLoadAt, now).compareTo(SETTINGS_REFRESH_EVERY) < 0) {
            return;
        }

        try {
            StrategySettings loaded = loadStrategySettings(chatId);
            RlAgentSettings cfg = rlSettingsService.getOrCreate(chatId);

            String fp = buildFingerprint(loaded, cfg);
            boolean changed = st.lastFingerprint == null || !Objects.equals(st.lastFingerprint, fp);

            String oldSymbol = safeUpper(st.symbol);

            st.ss = loaded;
            st.cfg = cfg;

            st.symbol = safeUpper(loaded.getSymbol());
            st.exchange = loaded.getExchangeName();
            st.network = loaded.getNetworkType();

            st.lastSettingsLoadAt = now;

            if (changed) {
                st.lastFingerprint = fp;

                int lookback = resolveLookback(loaded, cfg);
                log.info("[RL_AGENT] ⚙️ settings updated chatId={} symbol={} lookback={} minConf={}",
                        chatId, st.symbol, lookback, fmtBd(cfg.getMinConfidence()));

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    clearPosition(st);
                    st.lastHoldReason = null;
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[RL_AGENT] ⚠ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, RlAgentSettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = (ss != null && ss.getCachedCandlesLimit() != null) ? String.valueOf(ss.getCachedCandlesLimit()) : "null";

        String look = String.valueOf(resolveLookback(ss, cfg));
        String minc = cfg != null ? String.valueOf(cfg.getMinConfidence()) : "null";
        String agent = resolveAgentKey(cfg);

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + look + "|" + minc + "|" + agent;
    }

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.RL_AGENT)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("StrategySettings для RL_AGENT не найдены (chatId=" + chatId + ")"));
    }

    private void safeLive(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    private void pushHoldThrottled(Long chatId, String symbol, LocalState st, String reason, Instant now) {
        if (symbol == null) return;

        if (Objects.equals(st.lastHoldReason, reason) && st.lastHoldAt != null) {
            long ms = Duration.between(st.lastHoldAt, now).toMillis();
            if (ms < 2000) return;
        }

        st.lastHoldReason = reason;
        st.lastHoldAt = now;
        safeLive(() -> live.pushSignal(chatId, StrategyType.RL_AGENT, symbol, null, Signal.hold(reason)));
    }

    // =====================================================
    // RESOLVERS (чтобы не плодить новые классы и не ломать твою сущность RlAgentSettings)
    // =====================================================

    private static int resolveLookback(StrategySettings ss, RlAgentSettings cfg) {
        // у твоей RlAgentSettings сейчас НЕТ поля lookbackCandles — берём из StrategySettings.cachedCandlesLimit
        Integer fromSs = (ss != null ? ss.getCachedCandlesLimit() : null);
        int lookback = (fromSs != null && fromSs > 0) ? fromSs : 200;

        if (lookback < 50) lookback = 50;
        if (lookback > 2000) lookback = 2000;
        return lookback;
    }

    private static String resolveAgentKey(RlAgentSettings cfg) {
        // у твоей RlAgentSettings сейчас НЕТ agentKey — оставляем стабильный ключ для fingerprint/логов
        return "default";
    }

    // =====================================================
    // UTILS
    // =====================================================

    private static String safe(String s) {
        return s == null ? "null" : s.trim();
    }

    private static String safeUpper(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toUpperCase();
    }

    private static int nz(Integer v, int def) {
        return v != null ? v : def;
    }

    private static String fmtBd(BigDecimal v) {
        if (v == null) return "null";
        return v.stripTrailingZeros().toPlainString();
    }

    private static double normalizeThreshold(BigDecimal v) {
        if (v == null) return 0.60;
        double d = v.doubleValue();
        if (d > 1.0 && d <= 100.0) d = d / 100.0;
        if (d < 0.50) d = 0.50;
        if (d > 0.95) d = 0.95;
        return d;
    }

    private static double clamp01(double d) {
        if (!Double.isFinite(d)) return 0.0;
        if (d < 0.0) return 0.0;
        if (d > 1.0) return 1.0;
        return d;
    }

    private static double clamp01(BigDecimal v) {
        if (v == null) return 0.0;
        return clamp01(v.doubleValue());
    }

    private static String round2(double d) {
        return String.format(java.util.Locale.US, "%.2f", d);
    }
}
