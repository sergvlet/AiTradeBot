package com.chicu.aitradebot.strategy.dca;

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
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DCA Strategy (V4) — периодические покупки по рынку.
 *
 * Общие поля: StrategySettings(type=DCA)
 * Уникальные поля: DcaStrategySettings(intervalMinutes, orderVolume, optional tp/sl)
 *
 * Логика:
 * - если нет позиции и прошёл intervalMinutes с последней покупки → BUY
 * - выход только по TP/SL через TradeExecutionService.executeExitIfHit
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.DCA)
public class DcaStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 300;

    private final StrategyLivePublisher live;
    private final DcaStrategySettingsService dcaSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        DcaStrategySettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        String lastFingerprint;

        long ticks;
        long buys;
        long sells;

        // позиция
        boolean inPosition;
        BigDecimal entryQty;
        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;

        // таймер DCA
        Instant lastBuyAt;

        // hold throttling
        String lastHoldReason;
        Instant lastHoldAt;
    }

    // =====================================================
    // START / STOP
    // =====================================================

    @Override
    public void start(Long chatId, String ignored) {

        StrategySettings ss = loadStrategySettings(chatId);
        DcaStrategySettings cfg = dcaSettingsService.getOrCreate(chatId);

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

        log.info("[DCA] ▶ START chatId={} symbol={} intervalMin={} orderVol={}",
                chatId, st.symbol, nz(cfg.getIntervalMinutes(), 60), fmtBd(cfg.getOrderVolume()));

        final String symFinal = st.symbol; // ✅ фикс для лямбд
        safeLive(() -> live.pushState(chatId, StrategyType.DCA, symFinal, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.DCA, symFinal, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        final String symFinal = st.symbol; // ✅ фикс для лямбд

        if (symFinal != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.DCA, symFinal));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.DCA, symFinal));
            safeLive(() -> live.pushState(chatId, StrategyType.DCA, symFinal, false));
        }

        log.info("[DCA] ⏹ STOP chatId={} symbol={} ticks={} buys={} sells={} inPos={}",
                chatId, symFinal, st.ticks, st.buys, st.sells, st.inPosition);
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

        // ⚠️ тут символ может ещё поменяться в refreshSettingsIfNeeded — поэтому живые пуши делаем через финальную копию
        final String symPre = safeUpper(st.symbol);
        final String symPreFinal = symPre;
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.DCA, symPreFinal, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            // ✅ после refresh ещё раз берём финальный символ
            final String sym = safeUpper(st.symbol);
            final String symFinal = sym;

            StrategySettings ss = st.ss;
            DcaStrategySettings cfg = st.cfg;

            if (symFinal == null) {
                pushHoldThrottled(chatId, null, st, "no_symbol", time);
                return;
            }
            if (cfg == null) {
                pushHoldThrottled(chatId, symFinal, st, "no_dca_settings", time);
                return;
            }

            Integer intervalMin = nz(cfg.getIntervalMinutes(), 60);
            if (intervalMin < 1) {
                pushHoldThrottled(chatId, symFinal, st, "interval<1", time);
                return;
            }

            // лог раз в N тиков
            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[DCA] tick chatId={} sym={} price={} inPos={} lastBuyAt={}",
                        chatId, symFinal, fmtBd(price), st.inPosition, st.lastBuyAt);
            }

            // 1) EXIT по TP/SL
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.DCA,
                            symFinal,
                            price,
                            time,
                            false,
                            st.entryQty,
                            st.tp,
                            st.sl
                    );

                    if (ex.executed()) {
                        st.sells++;
                        log.info("[DCA] ✅ EXIT OK chatId={} sym={} price={} (tp={} sl={})",
                                chatId, symFinal, fmtBd(price), fmtBd(st.tp), fmtBd(st.sl));

                        clearPosition(st);

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.DCA, symFinal));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.DCA, symFinal));
                        safeLive(() -> live.pushSignal(chatId, StrategyType.DCA, symFinal, null,
                                Signal.sell(1.0, "tp_sl_exit")));
                        return;
                    }
                } catch (Exception e) {
                    log.error("[DCA] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
                }
            }

            // 2) ENTRY по таймеру (только если нет позиции)
            if (!st.inPosition) {

                if (st.lastBuyAt != null) {
                    long passedMin = Duration.between(st.lastBuyAt, time).toMinutes();
                    if (passedMin < intervalMin) {
                        pushHoldThrottled(chatId, symFinal, st, "waiting_interval", time);
                        return;
                    }
                }

                // score условный: чем дольше ждали — тем выше
                double score = 60.0;
                if (st.lastBuyAt != null) {
                    long passedMin = Duration.between(st.lastBuyAt, time).toMinutes();
                    score = Math.min(100.0, 50.0 + (passedMin - intervalMin) * 2.0);
                    if (score < 50) score = 50;
                }

                final double scoreFinal = score; // ✅ ВОТ ЭТО ДОБАВЬ

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.DCA,
                            symFinal,
                            price,
                            BigDecimal.valueOf(scoreFinal / 100.0),
                            time,
                            ss
                    );

                    if (!res.executed()) {
                        log.info("[DCA] ✋ BUY blocked chatId={} reason={}", chatId, res.reason());
                        pushHoldThrottled(chatId, symFinal, st, res.reason(), time);
                        return;
                    }

                    st.buys++;
                    st.lastBuyAt = time;

                    st.inPosition = true;
                    st.entryPrice = res.entryPrice();
                    st.entryQty = res.qty();

                    st.tp = res.tp();
                    st.sl = res.sl();

                    safeLive(() -> live.pushSignal(chatId, StrategyType.DCA, symFinal, null,
                            Signal.buy(scoreFinal, "dca_buy"))); // ✅ и тут scoreFinal
                    return;

                } catch (Exception e) {
                    log.error("[DCA] ❌ BUY failed chatId={} err={}", chatId, e.getMessage(), e);
                    pushHoldThrottled(chatId, symFinal, st, "buy_failed", time);
                    return;
                }

            }

            pushHoldThrottled(chatId, symFinal, st, "in_position", time);
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
    // SETTINGS REFRESH
    // =====================================================

    private void refreshSettingsIfNeeded(Long chatId, LocalState st, Instant now) {

        if (st.lastSettingsLoadAt != null &&
                Duration.between(st.lastSettingsLoadAt, now).compareTo(SETTINGS_REFRESH_EVERY) < 0) {
            return;
        }

        try {
            StrategySettings loaded = loadStrategySettings(chatId);
            DcaStrategySettings cfg = dcaSettingsService.getOrCreate(chatId);

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

                log.info("[DCA] ⚙️ settings updated chatId={} symbol={} intervalMin={} orderVol={}",
                        chatId,
                        st.symbol,
                        nz(cfg.getIntervalMinutes(), 60),
                        fmtBd(cfg.getOrderVolume())
                );

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    // сменили символ — сбрасываем позицию/таймер, чтобы не купить “по старому смыслу”
                    clearPosition(st);
                    st.lastBuyAt = null;
                    st.lastHoldReason = null;
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[DCA] ⚠ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, DcaStrategySettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex     = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net    = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf     = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = ss != null && ss.getCachedCandlesLimit() != null
                ? String.valueOf(ss.getCachedCandlesLimit())
                : "null";

        String interval = cfg != null ? String.valueOf(cfg.getIntervalMinutes()) : "null";
        String vol      = cfg != null ? String.valueOf(cfg.getOrderVolume()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + interval + "|" + vol;
    }

    // =====================================================
    // LOAD StrategySettings(type=DCA)
    // =====================================================

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.DCA)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для DCA не найдены (chatId=" + chatId + ")"
                ));
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

        final String symbolFinal = symbol; // ✅ на всякий случай
        safeLive(() -> live.pushSignal(chatId, StrategyType.DCA, symbolFinal, null, Signal.hold(reason)));
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
