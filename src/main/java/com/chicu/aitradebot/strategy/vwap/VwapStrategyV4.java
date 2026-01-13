package com.chicu.aitradebot.strategy.vwap;

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
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@StrategyBinding(StrategyType.VWAP)
@Slf4j
@Component
@RequiredArgsConstructor
public class VwapStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 250;

    private final StrategyLivePublisher live;
    private final VwapStrategySettingsService vwapSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    // ✅ ЕДИНЫЙ источник свечей в проекте
    private final CandleProvider candleProvider;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        VwapStrategySettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        String lastFingerprint;

        boolean inPosition;
        BigDecimal entryQty;
        BigDecimal tp;
        BigDecimal sl;

        Instant lastTradeClosedAt;

        long ticks;
        long warmups;
        long entries;
        long exits;

        String lastHoldReason;
        Instant lastHoldAt;
    }

    // =====================================================
    // START / STOP
    // =====================================================

    @Override
    public void start(Long chatId, String ignored) {
        StrategySettings ss = loadStrategySettings(chatId);
        VwapStrategySettings cfg = vwapSettingsService.getOrCreate(chatId);

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

        final String sym = st.symbol;

        log.info("[VWAP] ▶ START chatId={} symbol={} windowCandles={} entryDev={} exitDev={}",
                chatId,
                sym,
                cfg != null ? cfg.getWindowCandles() : null,
                cfg != null ? cfg.getEntryDeviationPct() : null,
                cfg != null ? cfg.getExitDeviationPct() : null
        );

        if (sym != null) {
            safeLive(() -> live.pushState(chatId, StrategyType.VWAP, sym, true));
            safeLive(() -> live.pushSignal(chatId, StrategyType.VWAP, sym, null, Signal.hold("started")));
        }
    }

    @Override
    public void stop(Long chatId, String ignored) {
        LocalState st = states.remove(chatId);
        if (st == null) return;

        final String sym = st.symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.VWAP, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.VWAP, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.VWAP, sym, false));
        }

        log.info("[VWAP] ⏹ STOP chatId={} symbol={} ticks={} warmups={} entries={} exits={} inPos={}",
                chatId, sym, st.ticks, st.warmups, st.entries, st.exits, st.inPosition);
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

    // =====================================================
    // PRICE UPDATE
    // =====================================================

    @Override
    public void onPriceUpdate(Long chatId, String symbolFromTick, BigDecimal price, Instant ts) {

        LocalState st = states.get(chatId);
        if (st == null || !st.active) return;

        st.ticks++;

        if (price == null || price.signum() <= 0) return;

        Instant time = (ts != null ? ts : Instant.now());

        String tickSymbol = safeUpper(symbolFromTick);
        String cfgSymbol = safeUpper(st.symbol);
        if (cfgSymbol != null && tickSymbol != null && !cfgSymbol.equals(tickSymbol)) return;
        if (cfgSymbol == null && tickSymbol != null) st.symbol = tickSymbol;

        final String sym = safeUpper(st.symbol);
        if (sym == null) return;

        safeLive(() -> live.pushPriceTick(chatId, StrategyType.VWAP, sym, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings ss = st.ss;
            VwapStrategySettings cfg = st.cfg;

            if (cfg == null) {
                pushHoldThrottled(chatId, sym, st, "no_vwap_settings", time);
                return;
            }

            Integer windowObj = cfg.getWindowCandles();
            int window = (windowObj != null ? windowObj : 0);
            if (window < 10) {
                pushHoldThrottled(chatId, sym, st, "window<10", time);
                return;
            }

            String timeframe = (ss != null ? safe(ss.getTimeframe()) : null);
            if (timeframe == null) {
                pushHoldThrottled(chatId, sym, st, "no_timeframe", time);
                return;
            }

            List<CandleProvider.Candle> candles = loadCandles(chatId, sym, timeframe, window);
            if (candles == null || candles.size() < Math.min(10, window)) {
                st.warmups++;
                pushHoldThrottled(chatId, sym, st, "warming_up", time);
                return;
            }

            BigDecimal vwap = computeVwap(candles);
            if (vwap == null || vwap.signum() <= 0) {
                pushHoldThrottled(chatId, sym, st, "vwap_invalid", time);
                return;
            }

            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[VWAP] tick chatId={} symbol={} price={} vwap={}",
                        chatId,
                        sym,
                        price.stripTrailingZeros().toPlainString(),
                        vwap.stripTrailingZeros().toPlainString()
                );
            }

            double entryDev = cfg.getEntryDeviationPct() != null ? cfg.getEntryDeviationPct() : 0.30;
            double exitDev = cfg.getExitDeviationPct() != null ? cfg.getExitDeviationPct() : 0.20;

            BigDecimal entryThreshold = vwap.multiply(BigDecimal.ONE.subtract(pct(entryDev)));
            BigDecimal exitThreshold = vwap.multiply(BigDecimal.ONE.add(pct(exitDev)));

            // =====================================================
            // ENTRY: price <= VWAP*(1-entryDev)
            // =====================================================
            if (!st.inPosition && price.compareTo(entryThreshold) <= 0) {

                double score = deviationScoreDown(price, vwap, entryDev);

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.VWAP,
                            sym,
                            price,
                            BigDecimal.valueOf(score / 100.0),
                            time,
                            ss
                    );

                    if (!res.executed()) {
                        pushHoldThrottled(chatId, sym, st, res.reason(), time);
                        return;
                    }

                    st.entries++;
                    st.inPosition = true;

                    st.entryQty = res.qty();
                    st.tp = res.tp();
                    st.sl = res.sl();

                    safeLive(() -> live.pushSignal(chatId, StrategyType.VWAP, sym, null,
                            Signal.buy(score, "price_below_vwap")));

                    st.lastHoldReason = null;

                } catch (Exception e) {
                    log.error("[VWAP] ❌ ENTRY failed chatId={} err={}", chatId, e.getMessage(), e);
                    pushHoldThrottled(chatId, sym, st, "entry_failed", time);
                }
            }

            // =====================================================
            // EXIT: TP/SL или мягко price >= VWAP*(1+exitDev)
            // =====================================================
            if (st.inPosition && st.entryQty != null) {

                // 1) TP/SL
                if (st.tp != null && st.sl != null) {
                    try {
                        var ex = tradeExecutionService.executeExitIfHit(
                                chatId,
                                StrategyType.VWAP,
                                sym,
                                price,
                                time,
                                true,
                                st.entryQty,
                                st.tp,
                                st.sl
                        );

                        if (ex.executed()) {
                            st.exits++;
                            clearPosition(st, time);

                            safeLive(() -> live.clearTpSl(chatId, StrategyType.VWAP, sym));
                            safeLive(() -> live.clearPriceLines(chatId, StrategyType.VWAP, sym));

                            safeLive(() -> live.pushSignal(chatId, StrategyType.VWAP, sym, null,
                                    Signal.sell(1.0, "tp_sl_exit")));
                            return;
                        }
                    } catch (Exception e) {
                        log.error("[VWAP] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
                    }
                }

                // 2) ✅ Мягкий выход БЕЗ executeExitMarket:
                //    используем executeExitIfHit, принудительно выставив tp=price и sl=price
                if (price.compareTo(exitThreshold) >= 0) {
                    try {
                        var ex2 = tradeExecutionService.executeExitIfHit(
                                chatId,
                                StrategyType.VWAP,
                                sym,
                                price,
                                time,
                                true,
                                st.entryQty,
                                price, // forced TP
                                price  // forced SL
                        );

                        if (ex2.executed()) {
                            st.exits++;
                            clearPosition(st, time);

                            safeLive(() -> live.clearTpSl(chatId, StrategyType.VWAP, sym));
                            safeLive(() -> live.clearPriceLines(chatId, StrategyType.VWAP, sym));

                            safeLive(() -> live.pushSignal(chatId, StrategyType.VWAP, sym, null,
                                    Signal.sell(100.0, "vwap_soft_exit")));
                        } else {
                            pushHoldThrottled(chatId, sym, st, "exit_soft_blocked", time);
                        }
                    } catch (Exception e) {
                        log.error("[VWAP] ❌ EXIT soft failed chatId={} err={}", chatId, e.getMessage(), e);
                    }
                }
            }
        }
    }

    private void clearPosition(LocalState st, Instant time) {
        st.inPosition = false;
        st.entryQty = null;
        st.tp = null;
        st.sl = null;
        st.lastTradeClosedAt = time;
    }

    // =====================================================
    // SETTINGS REFRESH
    // =====================================================

    private void refreshSettingsIfNeeded(Long chatId, LocalState st, Instant now) {

        if (st.lastSettingsLoadAt != null &&
            Duration.between(st.lastSettingsLoadAt, now).compareTo(SETTINGS_REFRESH_EVERY) < 0) {
            return;
        }

        try {
            StrategySettings loaded = loadStrategySettings(chatId);
            VwapStrategySettings cfg = vwapSettingsService.getOrCreate(chatId);

            String fp = buildFingerprint(loaded, cfg);
            boolean changed = st.lastFingerprint == null || !Objects.equals(st.lastFingerprint, fp);

            String oldSymbol = safeUpper(st.symbol);

            if (loaded != null) st.ss = loaded;
            if (cfg != null) st.cfg = cfg;

            if (loaded != null) {
                String loadedSymbol = safeUpper(loaded.getSymbol());
                if (loadedSymbol != null) st.symbol = loadedSymbol;
                if (loaded.getExchangeName() != null) st.exchange = loaded.getExchangeName();
                if (loaded.getNetworkType() != null) st.network = loaded.getNetworkType();
            }

            st.lastSettingsLoadAt = now;

            if (changed) {
                st.lastFingerprint = fp;

                log.info("[VWAP] ⚙️ settings updated chatId={} symbol={} window={} entryDev={} exitDev={}",
                        chatId,
                        st.symbol,
                        cfg != null ? cfg.getWindowCandles() : null,
                        cfg != null ? cfg.getEntryDeviationPct() : null,
                        cfg != null ? cfg.getExitDeviationPct() : null
                );

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    st.lastHoldReason = null;
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[VWAP] ⚠️ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, VwapStrategySettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = ss != null && ss.getCachedCandlesLimit() != null ? String.valueOf(ss.getCachedCandlesLimit()) : "null";

        String window = cfg != null ? String.valueOf(cfg.getWindowCandles()) : "null";
        String entry = cfg != null ? String.valueOf(cfg.getEntryDeviationPct()) : "null";
        String exit = cfg != null ? String.valueOf(cfg.getExitDeviationPct()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + window + "|" + entry + "|" + exit;
    }

    // =====================================================
    // LOAD StrategySettings
    // =====================================================

    private StrategySettings loadStrategySettings(Long chatId) {
        // ✅ самый новый: updatedAt DESC, затем id DESC
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.VWAP)
                .max(Comparator
                        .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                )
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для VWAP не найдены (chatId=" + chatId + ")"
                ));
    }

    // =====================================================
    // CANDLES + VWAP
    // =====================================================

    private List<CandleProvider.Candle> loadCandles(Long chatId, String symbol, String timeframe, int limit) {
        return candleProvider.getRecentCandles(chatId, symbol, timeframe, limit);
    }

    /**
     * VWAP по typical price: (H+L+C)/3
     */
    private static BigDecimal computeVwap(List<CandleProvider.Candle> candles) {
        if (candles == null || candles.isEmpty()) return null;

        BigDecimal pv = BigDecimal.ZERO;
        BigDecimal vv = BigDecimal.ZERO;

        for (CandleProvider.Candle c : candles) {
            if (c == null) continue;

            double vol = c.getVolume();
            if (vol <= 0) continue;

            double tp = (c.getHigh() + c.getLow() + c.getClose()) / 3.0;

            BigDecimal v = BigDecimal.valueOf(vol);
            BigDecimal p = BigDecimal.valueOf(tp);

            pv = pv.add(p.multiply(v));
            vv = vv.add(v);
        }

        if (vv.signum() <= 0) return null;
        return pv.divide(vv, 10, RoundingMode.HALF_UP);
    }

    // =====================================================
    // LIVE HELPERS
    // =====================================================

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

        safeLive(() -> live.pushSignal(chatId, StrategyType.VWAP, symbol, null, Signal.hold(reason)));
    }

    // =====================================================
    // UTILS
    // =====================================================

    private static String safe(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String safeUpper(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toUpperCase();
    }

    private static BigDecimal pct(double p) {
        // p = 0.30 означает 0.30%
        return BigDecimal.valueOf(p).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
    }

    private static double deviationScoreDown(BigDecimal price, BigDecimal vwap, double entryDevPct) {
        if (vwap == null || vwap.signum() <= 0) return 0;
        BigDecimal diff = vwap.subtract(price);
        if (diff.signum() <= 0) return 0;

        BigDecimal pct = diff.divide(vwap, 10, RoundingMode.HALF_UP); // 0..1
        BigDecimal target = BigDecimal.valueOf(entryDevPct).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        if (target.signum() <= 0) return 50;

        double score01 = pct.divide(target, 10, RoundingMode.HALF_UP).doubleValue();
        if (score01 < 0) score01 = 0;
        if (score01 > 2) score01 = 2;

        return Math.min(100.0, score01 * 50.0); // 0..100
    }
}
