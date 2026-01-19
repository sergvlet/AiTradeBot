package com.chicu.aitradebot.strategy.grid;

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

/**
 * GRID Strategy (V4) — SPOT LONG
 *
 * Источник общих полей: StrategySettings (type=GRID)
 * - exchangeName, networkType, symbol, timeframe, cachedCandlesLimit, active, cooldownSeconds, etc.
 *
 * Уникальные GRID поля: GridStrategySettings
 * - gridLevels, gridStepPct, orderVolume (+ опционально takeProfitPct/stopLossPct, если ты их оставил в GridStrategySettings)
 *
 * Логика:
 * - ждём "якорь" (anchorPrice) = первая цена после запуска/смены символа
 * - строим уровни: entryLevel = anchor*(1 - k*stepPct)
 * - вход: если цена <= entryLevel и не в позиции → executeEntry (объём/риски решает TradeExecutionService по StrategySettings)
 * - выход: только TP/SL через executeExitIfHit
 *
 * Примечание: это рабочий каркас “грид-накопления вниз” (mean-reversion grid).
 * Дальше можно расширять на выставление лимиток по уровням — но базовый вариант уже торгует.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.GRID)
public class GridStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 250;

    private final StrategyLivePublisher live;
    private final GridStrategySettingsService gridSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        GridStrategySettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        String lastFingerprint;

        long ticks;
        long warmups;
        long entries;
        long exits;

        // GRID anchor
        BigDecimal anchorPrice;

        // position
        boolean inPosition;
        boolean isLong;
        BigDecimal entryQty;
        BigDecimal entryPrice;
        BigDecimal tp;
        BigDecimal sl;

        Instant lastTradeClosedAt;

        String lastHoldReason;
        Instant lastHoldAt;
    }

    // =====================================================
    // START / STOP
    // =====================================================

    @Override
    public void start(Long chatId, String ignored) {

        StrategySettings ss = loadStrategySettings(chatId);
        GridStrategySettings cfg = gridSettingsService.getOrCreate(chatId);

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

        log.info("[GRID] ▶ START chatId={} symbol={} levels={} stepPct={} orderVol={}",
                chatId,
                st.symbol,
                nz(cfg.getGridLevels(), 10),
                fmtBd(cfg.getGridStepPct()),
                fmtBd(cfg.getOrderVolume())
        );

        final String sym = st.symbol;
        safeLive(() -> live.pushState(chatId, StrategyType.GRID, sym, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.GRID, sym, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        final String sym = st.symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.GRID, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.GRID, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.GRID, sym, false));
        }

        log.info("[GRID] ⏹ STOP chatId={} symbol={} ticks={} entries={} exits={} inPos={} anchor={}",
                chatId, sym, st.ticks, st.entries, st.exits, st.inPosition, fmtBd(st.anchorPrice));
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
                log.warn("[GRID] ⚠ invalid price chatId={} price={}", chatId, price);
            }
            return;
        }

        Instant time = (ts != null ? ts : Instant.now());

        // не мешаем символы
        String tickSymbol = safeUpper(symbolFromTick);
        String cfgSymbol = safeUpper(st.symbol);
        if (cfgSymbol != null && tickSymbol != null && !cfgSymbol.equals(tickSymbol)) return;
        if (cfgSymbol == null && tickSymbol != null) st.symbol = tickSymbol;

        final String symbolForLive = safeUpper(st.symbol);
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.GRID, symbolForLive, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings ss = st.ss;
            GridStrategySettings cfg = st.cfg;

            final String sym = safeUpper(st.symbol);
            if (sym == null) {
                pushHoldThrottled(chatId, symbolForLive, st, "no_symbol", time);
                return;
            }
            if (cfg == null) {
                pushHoldThrottled(chatId, sym, st, "no_grid_settings", time);
                return;
            }

            int levels = nz(cfg.getGridLevels(), 10);
            if (levels < 1) {
                pushHoldThrottled(chatId, sym, st, "levels<1", time);
                return;
            }

            BigDecimal stepPct = cfg.getGridStepPct();
            if (stepPct == null || stepPct.signum() <= 0) {
                pushHoldThrottled(chatId, sym, st, "stepPct<=0", time);
                return;
            }

            // anchor
            if (st.anchorPrice == null) {
                st.anchorPrice = price;
                st.warmups++;
                log.info("[GRID] ⚓ anchor set chatId={} symbol={} anchor={}", chatId, sym, fmtBd(st.anchorPrice));
                pushHoldThrottled(chatId, sym, st, "anchor_set", time);
                return;
            }

            // лог раз в N тиков
            if (st.ticks % LOG_EVERY_TICKS == 0) {
                BigDecimal entryLevel = calcEntryLevel(st.anchorPrice, stepPct, levels);
                log.info("[GRID] tick chatId={} sym={} price={} anchor={} entryLevel={} inPos={}",
                        chatId,
                        sym,
                        fmtBd(price),
                        fmtBd(st.anchorPrice),
                        fmtBd(entryLevel),
                        st.inPosition
                );
            }

            // cooldown
            if (!st.inPosition && ss != null) {
                Integer cooldown = ss.getCooldownSeconds();
                if (cooldown != null && cooldown > 0 && st.lastTradeClosedAt != null) {
                    long passed = Duration.between(st.lastTradeClosedAt, time).getSeconds();
                    if (passed < cooldown) {
                        pushHoldThrottled(chatId, sym, st, "cooldown", time);
                        return;
                    }
                }
            }

            BigDecimal entryLevel = calcEntryLevel(st.anchorPrice, stepPct, levels);

            // =====================================================
            // ENTRY: цена ниже самого нижнего уровня
            // =====================================================
            if (!st.inPosition && price.compareTo(entryLevel) <= 0) {

                double score = computeScore(price, st.anchorPrice, entryLevel);

                log.info("[GRID] ⚡ ENTRY try chatId={} sym={} price={} entryLevel={} score={}",
                        chatId, sym, fmtBd(price), fmtBd(entryLevel), String.format("%.2f", score));

                try {
                    var res = tradeExecutionService.executeEntry(
                            chatId,
                            StrategyType.GRID,
                            sym,
                            price,
                            BigDecimal.valueOf(score / 100.0), // 0..1
                            time,
                            ss
                    );

                    if (!res.executed()) {
                        log.info("[GRID] ✋ ENTRY blocked chatId={} reason={}", chatId, res.reason());
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

                    safeLive(() -> live.pushSignal(chatId, StrategyType.GRID, sym, null,
                            Signal.buy(score, "grid_entry")));

                    st.lastHoldReason = null;

                } catch (Exception e) {
                    log.error("[GRID] ❌ ENTRY failed chatId={} err={}", chatId, e.getMessage(), e);
                    pushHoldThrottled(chatId, sym, st, "entry_failed", time);
                }
            }

            // =====================================================
            // EXIT: TP/SL
            // =====================================================
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.GRID,
                            sym,
                            price,
                            time,
                            false,
                            st.entryQty,
                            st.tp,
                            st.sl
                    );

                    if (ex.executed()) {
                        st.exits++;

                        log.info("[GRID] ✅ EXIT OK chatId={} sym={} price={} (tp={} sl={})",
                                chatId, sym, fmtBd(price), fmtBd(st.tp), fmtBd(st.sl));

                        clearPosition(st, time);

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.GRID, sym));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.GRID, sym));

                        safeLive(() -> live.pushSignal(chatId, StrategyType.GRID, sym, null,
                                Signal.sell(1.0, "tp_sl_exit")));
                        return;
                    }

                } catch (Exception e) {
                    log.error("[GRID] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
                }
            }

            // иначе HOLD
            pushHoldThrottled(chatId, sym, st, st.inPosition ? "in_position" : "waiting_entry", time);
        }
    }

    private void clearPosition(LocalState st, Instant time) {
        st.inPosition = false;
        st.isLong = false;

        st.entryQty = null;
        st.entryPrice = null;
        st.tp = null;
        st.sl = null;

        st.lastTradeClosedAt = time;

        // ✅ после сделки можно обновить якорь к текущей рыночной логике:
        // - вариант 1: оставить как есть (якорь фиксированный)
        // - вариант 2: перезафиксировать якорь после выхода
        // Я делаю “умнее” для грида: якорь = null → установится на следующем тике.
        st.anchorPrice = null;
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
            GridStrategySettings cfg = gridSettingsService.getOrCreate(chatId);

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

                log.info("[GRID] ⚙️ settings updated chatId={} symbol={} levels={} stepPct={} orderVol={}",
                        chatId,
                        st.symbol,
                        cfg != null ? cfg.getGridLevels() : null,
                        cfg != null ? cfg.getGridStepPct() : null,
                        cfg != null ? cfg.getOrderVolume() : null
                );

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    // сменили символ — обнуляем anchor/позицию/hold
                    st.anchorPrice = null;
                    st.lastHoldReason = null;
                    if (st.inPosition) {
                        // не роняем насильно — но лучше не продолжать торговать “прошлой” позицией на новом символе
                        // оставим как есть: позицию закроет TP/SL по старому символу только если тики приходят того же символа.
                        // Тики другого символа мы игнорим.
                    }
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[GRID] ⚠ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private String buildFingerprint(StrategySettings ss, GridStrategySettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex     = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net    = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf     = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = ss != null && ss.getCachedCandlesLimit() != null ? String.valueOf(ss.getCachedCandlesLimit()) : "null";
        String cooldown = ss != null && ss.getCooldownSeconds() != null ? String.valueOf(ss.getCooldownSeconds()) : "null";

        String levels = cfg != null ? String.valueOf(cfg.getGridLevels()) : "null";
        String step   = cfg != null ? String.valueOf(cfg.getGridStepPct()) : "null";
        String vol    = cfg != null ? String.valueOf(cfg.getOrderVolume()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + cooldown
                + "|" + levels + "|" + step + "|" + vol;
    }

    // =====================================================
    // LOAD StrategySettings (type=GRID)
    // =====================================================

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.GRID)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для GRID не найдены (chatId=" + chatId + ")"
                ));
    }

    // =====================================================
    // GRID math
    // =====================================================

    /**
     * entryLevel = anchor * (1 - levels * stepPct/100)
     */
    private static BigDecimal calcEntryLevel(BigDecimal anchor, BigDecimal stepPct, int levels) {
        BigDecimal step = stepPct.divide(BigDecimal.valueOf(100), 16, RoundingMode.HALF_UP);
        BigDecimal down = step.multiply(BigDecimal.valueOf(levels));
        BigDecimal factor = BigDecimal.ONE.subtract(down);
        // защита от отрицательного/нуля
        if (factor.signum() <= 0) factor = new BigDecimal("0.00000001");
        return anchor.multiply(factor).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * score 0..100: чем глубже падение от anchor к entryLevel — тем выше score.
     */
    private static double computeScore(BigDecimal price, BigDecimal anchor, BigDecimal entryLevel) {
        if (price == null || anchor == null || entryLevel == null) return 0.0;
        BigDecimal range = anchor.subtract(entryLevel);
        if (range.signum() <= 0) return 0.0;
        BigDecimal diff = anchor.subtract(price);
        BigDecimal v = diff.divide(range, 8, RoundingMode.HALF_UP);
        double d = v.doubleValue();
        if (d < 0) d = 0;
        if (d > 1) d = 1;
        return d * 100.0;
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

        safeLive(() -> live.pushSignal(chatId, StrategyType.GRID, symbol, null, Signal.hold(reason)));
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
