package com.chicu.aitradebot.strategy.rsiobos;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@StrategyBinding(StrategyType.RSI_OBOS)
@Slf4j
@Component
@RequiredArgsConstructor
public class RsiObosStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 250;

    private final StrategyLivePublisher live;
    private final RsiObosStrategySettingsService rsiSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        RsiObosStrategySettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        Instant lastCfgUpdatedAt;
        String lastFingerprint;

        // RSI state
        BigDecimal lastPrice;
        BigDecimal avgGain;
        BigDecimal avgLoss;
        int rsiWarmup;

        boolean inPosition;
        boolean isLong;

        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;
        BigDecimal entryQty;
        Long entryOrderId;

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
        RsiObosStrategySettings cfg = rsiSettingsService.getOrCreate(chatId);

        LocalState st = new LocalState();
        st.active = true;
        st.startedAt = Instant.now();

        st.ss = ss;
        st.cfg = cfg;

        st.symbol = safeUpper(ss.getSymbol());
        st.exchange = ss.getExchangeName();
        st.network = ss.getNetworkType();

        st.lastSettingsLoadAt = Instant.now();
        st.lastCfgUpdatedAt = cfg != null ? cfg.getUpdatedAt() : null;
        st.lastFingerprint = buildFingerprint(ss, cfg);

        st.lastPrice = null;
        st.avgGain = null;
        st.avgLoss = null;
        st.rsiWarmup = 0;

        states.put(chatId, st);

        log.info("[RSI_OBOS] ▶ START chatId={} symbol={} rsiP={} buyBelow={} blockAbove={}",
                chatId,
                st.symbol,
                cfg != null ? cfg.getRsiPeriod() : null,
                cfg != null ? cfg.getBuyBelow() : null,
                cfg != null ? cfg.getBlockAbove() : null
        );

        final String sym = st.symbol;
        safeLive(() -> live.pushState(chatId, StrategyType.RSI_OBOS, sym, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.RSI_OBOS, sym, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        final String sym = st.symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.RSI_OBOS, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.RSI_OBOS, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.RSI_OBOS, sym, false));
        }

        log.info("[RSI_OBOS] ⏹ STOP chatId={} symbol={} ticks={} warmups={} entries={} exits={} inPos={}",
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

        if (price == null || price.signum() <= 0) {
            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.warn("[RSI_OBOS] ⚠ Invalid price chatId={} price={}", chatId, price);
            }
            return;
        }

        Instant time = ts != null ? ts : Instant.now();

        // не мешаем символы
        String tickSymbol = safeUpper(symbolFromTick);
        String cfgSymbol = safeUpper(st.symbol);
        if (cfgSymbol != null && tickSymbol != null && !cfgSymbol.equals(tickSymbol)) return;
        if (cfgSymbol == null && tickSymbol != null) st.symbol = tickSymbol;

        final String symbolForLive = safeUpper(st.symbol);
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.RSI_OBOS, symbolForLive, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings ss = st.ss;
            RsiObosStrategySettings cfg = st.cfg;

            final String sym = safeUpper(st.symbol);

            if (cfg == null) {
                pushHoldThrottled(chatId, sym, st, "no_rsi_settings", time);
                return;
            }

            int rsiP = cfg.getRsiPeriod() != null ? cfg.getRsiPeriod() : 0;
            if (rsiP < 2) {
                pushHoldThrottled(chatId, sym, st, "rsiPeriod<2", time);
                return;
            }

            updateRsi(st, price, rsiP);

            if (st.rsiWarmup < rsiP) {
                st.warmups++;
                pushHoldThrottled(chatId, sym, st, "warming_up", time);
                return;
            }

            double rsi = calcRsi(st);
            double buyBelow = nz(cfg.getBuyBelow());
            double blockAbove = nz(cfg.getBlockAbove());

            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[RSI_OBOS] tick chatId={} symbol={} price={} rsi={} buyBelow={} blockAbove={}",
                        chatId,
                        sym,
                        price.stripTrailingZeros().toPlainString(),
                        fmt(rsi),
                        fmt(buyBelow),
                        fmt(blockAbove));
            }

            // =====================================================
            // ENTRY: RSI <= buyBelow
            // =====================================================
            if (!st.inPosition) {

                if (blockAbove > 0 && rsi >= blockAbove) {
                    pushHoldThrottled(chatId, sym, st, "rsi_too_high", time);
                    return;
                }

                Integer cooldown = ss != null ? ss.getCooldownSeconds() : null;
                if (cooldown != null && cooldown > 0 && st.lastTradeClosedAt != null) {
                    long passed = Duration.between(st.lastTradeClosedAt, time).getSeconds();
                    if (passed < cooldown) {
                        pushHoldThrottled(chatId, sym, st, "cooldown", time);
                        return;
                    }
                }

                if (rsi <= buyBelow) {

                    // score: чем ниже RSI — тем выше
                    final double score = clamp01((buyBelow - rsi) / Math.max(1.0, buyBelow)) * 100.0;

                    log.info("[RSI_OBOS] ⚡ ENTRY try chatId={} symbol={} price={} rsi={}",
                            chatId, sym, price.stripTrailingZeros().toPlainString(), fmt(rsi));

                    try {
                        var res = tradeExecutionService.executeEntry(
                                chatId,
                                StrategyType.RSI_OBOS,
                                sym,
                                price,
                                BigDecimal.valueOf(rsi),
                                time,
                                ss
                        );

                        if (!res.executed()) {
                            log.info("[RSI_OBOS] ✋ ENTRY blocked chatId={} reason={}", chatId, res.reason());
                            pushHoldThrottled(chatId, sym, st, res.reason(), time);
                            return;
                        }

                        st.entries++;
                        st.inPosition = true;
                        st.isLong = true;

                        st.entryPrice = res.entryPrice();
                        st.tp = res.tp();
                        st.sl = res.sl();
                        st.entryQty = res.qty();
                        st.entryOrderId = res.orderId();

                        safeLive(() -> live.pushSignal(chatId, StrategyType.RSI_OBOS, sym, null,
                                Signal.buy(score, "rsi_oversold")));

                        st.lastHoldReason = null;

                    } catch (Exception e) {
                        log.error("[RSI_OBOS] ❌ ENTRY failed chatId={} err={}", chatId, e.getMessage(), e);
                        pushHoldThrottled(chatId, sym, st, "entry_failed", time);
                    }
                } else {
                    pushHoldThrottled(chatId, sym, st, "rsi_no_entry", time);
                }
            }

            // =====================================================
            // EXIT: TP/SL
            // =====================================================
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {

                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.RSI_OBOS,
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

                        log.info("[RSI_OBOS] ✅ EXIT OK chatId={} price={} (tp={} sl={})",
                                chatId,
                                price.stripTrailingZeros().toPlainString(),
                                st.tp.stripTrailingZeros().toPlainString(),
                                st.sl.stripTrailingZeros().toPlainString()
                        );

                        st.inPosition = false;
                        st.entryQty = null;
                        st.entryOrderId = null;
                        st.entryPrice = null;
                        st.tp = null;
                        st.sl = null;

                        st.lastTradeClosedAt = time;

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.RSI_OBOS, sym));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.RSI_OBOS, sym));

                        safeLive(() -> live.pushSignal(chatId, StrategyType.RSI_OBOS, sym, null,
                                Signal.sell(1.0, "tp_sl_exit")));
                    }

                } catch (Exception e) {
                    log.error("[RSI_OBOS] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
                }
            }
        }
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
            RsiObosStrategySettings cfg = rsiSettingsService.getOrCreate(chatId);

            Instant cfgUpd = cfg != null ? cfg.getUpdatedAt() : null;
            String fp = buildFingerprint(loaded, cfg);

            boolean changed =
                    st.lastFingerprint == null ||
                    !Objects.equals(st.lastFingerprint, fp) ||
                    !Objects.equals(st.lastCfgUpdatedAt, cfgUpd);

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
                st.lastCfgUpdatedAt = cfgUpd;

                log.info("[RSI_OBOS] ⚙️ settings updated chatId={} symbol={} rsiP={} buyBelow={} blockAbove={}",
                        chatId,
                        st.symbol,
                        cfg != null ? cfg.getRsiPeriod() : null,
                        cfg != null ? cfg.getBuyBelow() : null,
                        cfg != null ? cfg.getBlockAbove() : null
                );

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    // смена символа: сброс RSI
                    st.lastPrice = null;
                    st.avgGain = null;
                    st.avgLoss = null;
                    st.rsiWarmup = 0;
                    st.lastHoldReason = null;

                    final String old = oldSymbol;
                    safeLive(() -> live.clearTpSl(chatId, StrategyType.RSI_OBOS, old));
                    safeLive(() -> live.clearPriceLines(chatId, StrategyType.RSI_OBOS, old));
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[RSI_OBOS] ⚠️ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, RsiObosStrategySettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex     = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net    = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf     = ss != null ? safe(ss.getTimeframe()) : "null";

        String candles  = ss != null && ss.getCachedCandlesLimit() != null ? String.valueOf(ss.getCachedCandlesLimit()) : "null";
        String cooldown = ss != null && ss.getCooldownSeconds() != null ? String.valueOf(ss.getCooldownSeconds()) : "null";

        String p = cfg != null && cfg.getRsiPeriod() != null ? String.valueOf(cfg.getRsiPeriod()) : "null";
        String b = cfg != null && cfg.getBuyBelow() != null ? String.valueOf(cfg.getBuyBelow()) : "null";
        String ba = cfg != null && cfg.getBlockAbove() != null ? String.valueOf(cfg.getBlockAbove()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + cooldown
                + "|" + p + "|" + b + "|" + ba;
    }

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.RSI_OBOS)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для RSI_OBOS не найдены (chatId=" + chatId + ")"
                ));
    }

    // =====================================================
    // RSI helpers (Wilder)
    // =====================================================

    private static void updateRsi(LocalState st, BigDecimal price, int period) {
        if (st.lastPrice == null) {
            st.lastPrice = price;
            return;
        }

        BigDecimal change = price.subtract(st.lastPrice);
        st.lastPrice = price;

        BigDecimal gain = change.signum() > 0 ? change : BigDecimal.ZERO;
        BigDecimal loss = change.signum() < 0 ? change.abs() : BigDecimal.ZERO;

        if (st.avgGain == null || st.avgLoss == null) {
            st.avgGain = gain;
            st.avgLoss = loss;
            st.rsiWarmup = Math.min(st.rsiWarmup + 1, period);
            return;
        }

        BigDecimal p = BigDecimal.valueOf(period);

        st.avgGain = st.avgGain.multiply(BigDecimal.valueOf(period - 1))
                .add(gain)
                .divide(p, 10, RoundingMode.HALF_UP);

        st.avgLoss = st.avgLoss.multiply(BigDecimal.valueOf(period - 1))
                .add(loss)
                .divide(p, 10, RoundingMode.HALF_UP);

        st.rsiWarmup = Math.min(st.rsiWarmup + 1, period);
    }

    private static double calcRsi(LocalState st) {
        if (st.avgGain == null || st.avgLoss == null) return 50.0;
        if (st.avgLoss.signum() == 0) return 100.0;

        BigDecimal rs = st.avgGain.divide(st.avgLoss, 10, RoundingMode.HALF_UP);
        double rsD = rs.doubleValue();
        return 100.0 - (100.0 / (1.0 + rsD));
    }

    // =====================================================
    // misc helpers
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

        safeLive(() -> live.pushSignal(chatId, StrategyType.RSI_OBOS, symbol, null, Signal.hold(reason)));
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private static String safe(String s) {
        return s == null ? "null" : s.trim();
    }

    private static String safeUpper(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toUpperCase();
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }
}
