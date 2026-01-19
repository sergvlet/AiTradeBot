// src/main/java/com/chicu/aitradebot/strategy/volume/VolumeProfileStrategyV4.java
package com.chicu.aitradebot.strategy.volume;

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

/**
 * VOLUME_PROFILE Strategy (V4)
 *
 * Общие поля: StrategySettings(type=VOLUME_PROFILE)
 * Уникальные поля: VolumeProfileStrategySettings(lookbackCandles,bins,valueAreaPct,entryMode)
 *
 * Профиль объёма строим по свечам за lookback:
 * - диапазон minLow..maxHigh
 * - делим на bins
 * - распределяем объём свечи в bin по типичной цене (hlc3)
 *
 * Вычисляем:
 * - POC (bin с max vol)
 * - VA (Value Area) вокруг POC на valueAreaPct (например 70% суммарного объёма)
 *
 * Сигналы:
 * - MEAN_REVERT: BUY ниже VAL, SELL выше VAH
 * - BREAKOUT: BUY выше VAH, SELL ниже VAL
 *
 * Реальная торговля:
 * - вход: tradeExecutionService.executeEntry(...)
 * - выход: только TP/SL через executeExitIfHit(...)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.VOLUME_PROFILE)
public class VolumeProfileStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final Duration PROFILE_REBUILD_EVERY = Duration.ofSeconds(5);
    private static final long LOG_EVERY_TICKS = 300;

    private final StrategyLivePublisher live;
    private final VolumeProfileStrategySettingsService vpSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    // ✅ ЕДИНЫЙ поставщик свечей
    private final CandleProvider candleProvider;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        VolumeProfileStrategySettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        String lastFingerprint;

        long ticks;

        // позиция
        boolean inPosition;
        BigDecimal entryQty;
        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;

        // профиль
        Instant lastProfileBuildAt;
        Profile profile;

        // hold throttling
        String lastHoldReason;
        Instant lastHoldAt;
    }

    private static class Profile {
        BigDecimal min;
        BigDecimal max;
        int bins;

        BigDecimal poc;
        BigDecimal val;
        BigDecimal vah;

        BigDecimal totalVol;

        Instant builtAt;
    }

    // =====================================================
    // START / STOP
    // =====================================================

    @Override
    public void start(Long chatId, String ignored) {

        StrategySettings ss = loadStrategySettings(chatId);
        VolumeProfileStrategySettings cfg = vpSettingsService.getOrCreate(chatId);

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

        log.info("[VOLUME_PROFILE] ▶ START chatId={} symbol={} bins={} VA%={} mode={}",
                chatId,
                st.symbol,
                nz(cfg.getBins(), 48),
                fmtBd(cfg.getValueAreaPct()),
                cfg.getEntryMode());

        safeLive(() -> live.pushState(chatId, StrategyType.VOLUME_PROFILE, st.symbol, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.VOLUME_PROFILE, st.symbol, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        String sym = st.symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.VOLUME_PROFILE, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.VOLUME_PROFILE, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.VOLUME_PROFILE, sym, false));
        }

        log.info("[VOLUME_PROFILE] ⏹ STOP chatId={} symbol={} ticks={} inPos={}",
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

        String tickSym = safeUpper(symbolFromTick);
        String cfgSym = safeUpper(st.symbol);
        if (cfgSym != null && tickSym != null && !cfgSym.equals(tickSym)) return;
        if (cfgSym == null && tickSym != null) st.symbol = tickSym;

        final String symFinal = safeUpper(st.symbol);
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.VOLUME_PROFILE, symFinal, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings ss = st.ss;
            VolumeProfileStrategySettings cfg = st.cfg;

            if (symFinal == null) {
                pushHoldThrottled(chatId, null, st, "no_symbol", time);
                return;
            }
            if (cfg == null) {
                pushHoldThrottled(chatId, symFinal, st, "no_vp_settings", time);
                return;
            }
            if (ss == null || ss.getTimeframe() == null || ss.getTimeframe().trim().isEmpty()) {
                pushHoldThrottled(chatId, symFinal, st, "no_timeframe", time);
                return;
            }

            if (st.ticks % LOG_EVERY_TICKS == 0) {
                Profile p = st.profile;
                log.info("[VOLUME_PROFILE] tick chatId={} sym={} price={} poc={} val={} vah={} inPos={}",
                        chatId,
                        symFinal,
                        fmtBd(price),
                        p != null ? fmtBd(p.poc) : "null",
                        p != null ? fmtBd(p.val) : "null",
                        p != null ? fmtBd(p.vah) : "null",
                        st.inPosition);
            }

            // 1) EXIT TP/SL
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.VOLUME_PROFILE,
                            symFinal,
                            price,
                            time,
                            false,
                            st.entryQty,
                            st.tp,
                            st.sl
                    );

                    if (ex.executed()) {
                        log.info("[VOLUME_PROFILE] ✅ EXIT OK chatId={} sym={} price={} (tp={} sl={})",
                                chatId, symFinal, fmtBd(price), fmtBd(st.tp), fmtBd(st.sl));

                        clearPosition(st);

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.VOLUME_PROFILE, symFinal));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.VOLUME_PROFILE, symFinal));
                        safeLive(() -> live.pushSignal(chatId, StrategyType.VOLUME_PROFILE, symFinal, null, Signal.sell(1.0, "tp_sl_exit")));
                        return;
                    }
                } catch (Exception e) {
                    log.error("[VOLUME_PROFILE] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
                }
            }

            // 2) build profile periodically
            if (st.lastProfileBuildAt == null ||
                Duration.between(st.lastProfileBuildAt, time).compareTo(PROFILE_REBUILD_EVERY) >= 0) {

                Profile built = tryBuildProfile(chatId, ss, cfg, symFinal, time);
                st.profile = built;
                st.lastProfileBuildAt = time;
            }

            Profile p = st.profile;
            if (p == null || p.val == null || p.vah == null) {
                pushHoldThrottled(chatId, symFinal, st, "profile_not_ready", time);
                return;
            }

            // 3) generate signal from VA bands
            final VolumeProfileStrategySettings.EntryMode modeFinal =
                    (cfg.getEntryMode() != null)
                            ? cfg.getEntryMode()
                            : VolumeProfileStrategySettings.EntryMode.MEAN_REVERT;

            final boolean buySignal;
            final boolean sellSignal;

            if (modeFinal == VolumeProfileStrategySettings.EntryMode.MEAN_REVERT) {
                buySignal = price.compareTo(p.val) < 0;
                sellSignal = price.compareTo(p.vah) > 0;
            } else {
                buySignal = price.compareTo(p.vah) > 0;
                sellSignal = price.compareTo(p.val) < 0;
            }

            // 4) ENTRY (только если не в позиции)
            if (!st.inPosition && buySignal) {

                double score = 70.0;

                BigDecimal dist = (modeFinal == VolumeProfileStrategySettings.EntryMode.MEAN_REVERT)
                        ? p.val.subtract(price).abs()
                        : price.subtract(p.vah).abs();

                if (p.max != null && p.min != null && p.max.compareTo(p.min) > 0) {
                    BigDecimal range = p.max.subtract(p.min);
                    BigDecimal rel = dist.divide(range, 18, RoundingMode.HALF_UP);
                    score = Math.min(100.0, 60.0 + rel.doubleValue() * 200.0);
                }
                if (score < 50.0) score = 50.0;

                final double scoreFinal = score;
                final String buyReason =
                        (modeFinal == VolumeProfileStrategySettings.EntryMode.MEAN_REVERT)
                                ? "below_VAL"
                                : "above_VAH";

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.VOLUME_PROFILE,
                            symFinal,
                            price,
                            BigDecimal.valueOf(scoreFinal / 100.0),
                            time,
                            ss
                    );

                    if (!res.executed()) {
                        log.info("[VOLUME_PROFILE] ✋ BUY blocked chatId={} reason={}", chatId, res.reason());
                        pushHoldThrottled(chatId, symFinal, st, res.reason(), time);
                        return;
                    }

                    st.inPosition = true;
                    st.entryPrice = res.entryPrice();
                    st.entryQty = res.qty();
                    st.tp = res.tp();
                    st.sl = res.sl();

                    safeLive(() -> live.pushSignal(
                            chatId,
                            StrategyType.VOLUME_PROFILE,
                            symFinal,
                            null,
                            Signal.buy(scoreFinal, buyReason)
                    ));
                    return;

                } catch (Exception e) {
                    log.error("[VOLUME_PROFILE] ❌ BUY failed chatId={} err={}", chatId, e.getMessage(), e);
                    pushHoldThrottled(chatId, symFinal, st, "buy_failed", time);
                    return;
                }
            }

            // 5) SELL сигнал — здесь только информируем (выход по TP/SL)
            if (st.inPosition && sellSignal) {
                final String sellHoldReason =
                        (modeFinal == VolumeProfileStrategySettings.EntryMode.MEAN_REVERT)
                                ? "above_VAH_hold"
                                : "below_VAL_hold";
                pushHoldThrottled(chatId, symFinal, st, sellHoldReason, time);
                return;
            }

            pushHoldThrottled(chatId, symFinal, st, "no_signal", time);
        }
    }

    private void clearPosition(LocalState st) {
        st.inPosition = false;
        st.entryQty = null;
        st.entryPrice = null;
        st.tp = null;
        st.sl = null;
    }

    // =====================================================
    // BUILD PROFILE (через CandleProvider)
    // =====================================================

    private Profile tryBuildProfile(Long chatId,
                                    StrategySettings ss,
                                    VolumeProfileStrategySettings cfg,
                                    String symbol,
                                    Instant now) {
        try {
            int bins = nz(cfg.getBins(), 48);
            if (bins < 10) bins = 10;
            if (bins > 200) bins = 200;

            int lookback = resolveLookback(ss, cfg);

            String tf = ss.getTimeframe();
            List<CandleProvider.Candle> candles = candleProvider.getRecentCandles(chatId, symbol, tf, lookback);

            if (candles == null || candles.size() < Math.min(30, Math.max(20, lookback / 2))) {
                return null;
            }

            double minLow = Double.POSITIVE_INFINITY;
            double maxHigh = Double.NEGATIVE_INFINITY;

            for (CandleProvider.Candle c : candles) {
                double low = c.low();
                double high = c.high();
                if (Double.isNaN(low) || Double.isNaN(high)) continue;

                if (low < minLow) minLow = low;
                if (high > maxHigh) maxHigh = high;
            }

            if (!Double.isFinite(minLow) || !Double.isFinite(maxHigh)) return null;
            if (maxHigh <= minLow) return null;

            double range = maxHigh - minLow;
            double binSize = range / (double) bins;
            if (!(binSize > 0.0)) return null;

            double[] volBins = new double[bins];
            double totalVol = 0.0;

            for (CandleProvider.Candle c : candles) {
                double high = c.high();
                double low = c.low();
                double close = c.close();
                double vol = c.volume();

                if (!(vol > 0.0)) continue;
                if (!Double.isFinite(high) || !Double.isFinite(low) || !Double.isFinite(close)) continue;

                double tp = (high + low + close) / 3.0; // hlc3
                int idx = (int) Math.floor((tp - minLow) / binSize);

                if (idx < 0) idx = 0;
                if (idx >= bins) idx = bins - 1;

                volBins[idx] += vol;
                totalVol += vol;
            }

            if (!(totalVol > 0.0)) return null;

            // POC
            int pocIdx = 0;
            double pocVol = volBins[0];
            for (int i = 1; i < bins; i++) {
                if (volBins[i] > pocVol) {
                    pocVol = volBins[i];
                    pocIdx = i;
                }
            }

            double vaPct = normalizeVaPct(cfg.getValueAreaPct());
            double target = totalVol * (vaPct / 100.0);

            // VA вокруг POC
            int left = pocIdx;
            int right = pocIdx;
            double acc = volBins[pocIdx];

            while (acc < target && (left > 0 || right < bins - 1)) {

                double leftNext = (left > 0) ? volBins[left - 1] : -1.0;
                double rightNext = (right < bins - 1) ? volBins[right + 1] : -1.0;

                boolean takeLeft = left > 0 && (right == bins - 1 || leftNext >= rightNext);

                if (takeLeft) {
                    left--;
                    acc += volBins[left];
                } else {
                    right++;
                    acc += volBins[right];
                }
            }

            Profile p = new Profile();
            p.min = bd(minLow);
            p.max = bd(maxHigh);
            p.bins = bins;
            p.totalVol = bd(totalVol);

            p.poc = bd(priceAtBinCenter(minLow, binSize, pocIdx));
            p.val = bd(priceAtBinCenter(minLow, binSize, left));
            p.vah = bd(priceAtBinCenter(minLow, binSize, right));

            p.builtAt = now;

            log.debug("[VOLUME_PROFILE] profile built chatId={} sym={} lookback={} bins={} POC={} VAL={} VAH={}",
                    chatId, symbol, lookback, bins, fmtBd(p.poc), fmtBd(p.val), fmtBd(p.vah));

            return p;

        } catch (Exception e) {
            log.warn("[VOLUME_PROFILE] profile build failed chatId={} sym={} err={}",
                    chatId, symbol, e.toString());
            return null;
        }
    }

    private static double normalizeVaPct(BigDecimal v) {
        if (v == null) return 70.0;

        double d = v.doubleValue();

        // если кто-то хранит 0.7 вместо 70
        if (d > 0.0 && d <= 1.0) d = d * 100.0;

        if (d < 50.0) d = 50.0;
        if (d > 95.0) d = 95.0;

        return d;
    }

    private static double priceAtBinCenter(double min, double binSize, int idx) {
        return min + (binSize * idx) + (binSize / 2.0);
    }

    private static BigDecimal bd(double v) {
        if (!Double.isFinite(v)) return null;
        return BigDecimal.valueOf(v).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static int resolveLookback(StrategySettings ss, VolumeProfileStrategySettings cfg) {
        Integer fromCfg = cfg.getLookbackCandles();
        if (fromCfg != null && fromCfg > 0) return clamp(fromCfg, 50, 2000);

        Integer fromSs = ss != null ? ss.getCachedCandlesLimit() : null;
        if (fromSs != null && fromSs > 0) return clamp(fromSs, 50, 300);

        return 200;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
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
            VolumeProfileStrategySettings cfg = vpSettingsService.getOrCreate(chatId);

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

                log.info("[VOLUME_PROFILE] ⚙️ settings updated chatId={} symbol={} lookback={} bins={} VA%={} mode={}",
                        chatId,
                        st.symbol,
                        cfg.getLookbackCandles(),
                        nz(cfg.getBins(), 48),
                        fmtBd(cfg.getValueAreaPct()),
                        cfg.getEntryMode());

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    clearPosition(st);
                    st.profile = null;
                    st.lastProfileBuildAt = null;
                    st.lastHoldReason = null;
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[VOLUME_PROFILE] ⚠ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, VolumeProfileStrategySettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = (ss != null && ss.getCachedCandlesLimit() != null) ? String.valueOf(ss.getCachedCandlesLimit()) : "null";

        String look = cfg != null ? String.valueOf(cfg.getLookbackCandles()) : "null";
        String bins = cfg != null ? String.valueOf(cfg.getBins()) : "null";
        String va = cfg != null ? String.valueOf(cfg.getValueAreaPct()) : "null";
        String mode = (cfg != null && cfg.getEntryMode() != null) ? cfg.getEntryMode().name() : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + look + "|" + bins + "|" + va + "|" + mode;
    }

    // =====================================================
    // LOAD StrategySettings(type=VOLUME_PROFILE)
    // =====================================================

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.VOLUME_PROFILE)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для VOLUME_PROFILE не найдены (chatId=" + chatId + ")"
                ));
    }

    // =====================================================
    // LIVE HELPERS
    // =====================================================

    private void safeLive(Runnable r) {
        try {
            r.run();
        } catch (Exception ignored) {
        }
    }

    private void pushHoldThrottled(Long chatId, String symbol, LocalState st, String reason, Instant now) {
        if (symbol == null) return;

        if (Objects.equals(st.lastHoldReason, reason) && st.lastHoldAt != null) {
            long ms = Duration.between(st.lastHoldAt, now).toMillis();
            if (ms < 2000) return;
        }

        st.lastHoldReason = reason;
        st.lastHoldAt = now;

        safeLive(() -> live.pushSignal(chatId, StrategyType.VOLUME_PROFILE, symbol, null, Signal.hold(reason)));
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
}
