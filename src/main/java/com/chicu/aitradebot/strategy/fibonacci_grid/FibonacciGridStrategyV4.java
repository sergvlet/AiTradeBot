// src/main/java/com/chicu/aitradebot/strategy/fibonacci_grid/FibonacciGridStrategyV4.java
package com.chicu.aitradebot.strategy.fibonacci_grid;

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
 * Fibonacci Grid (V4) — упрощённая “сеточная” логика:
 * - Берём базовую цену (first tick после start)
 * - Строим N buy-уровней вниз с шагом distancePct
 * - Когда цена <= уровню и этот уровень ещё не отрабатывался — делаем BUY (через TradeExecutionService.executeEntry)
 * - Выход — через executeExitIfHit по TP/SL (TP/SL определяются твоим TradeExecutionService из StrategySettings)
 *
 * Важно: это “рабочий скелет”, не лимитки, не OCO-сетка. Но он полностью V4-совместим и без хардкода.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.FIBONACCI_GRID)
public class FibonacciGridStrategyV4 implements TradingStrategy {

    private static final Duration SETTINGS_REFRESH_EVERY = Duration.ofSeconds(10);
    private static final long LOG_EVERY_TICKS = 300;

    private final StrategyLivePublisher live;
    private final FibonacciGridStrategySettingsService fiboSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings ss;
        FibonacciGridStrategySettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        Instant lastSettingsLoadAt;
        String lastFingerprint;

        long ticks;
        long buys;
        long sells;

        // базовая цена сетки
        BigDecimal basePrice;

        // какие уровни уже “сработали”
        boolean[] levelFired;

        // позиция (кумулятивно)
        boolean inPosition;
        BigDecimal entryQty;
        BigDecimal entryPrice; // средняя цена
        BigDecimal tp;
        BigDecimal sl;

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
        FibonacciGridStrategySettings cfg = fiboSettingsService.getOrCreate(chatId);

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

        int levels = nz(cfg.getGridLevels(), 6);
        st.levelFired = new boolean[Math.max(1, levels)];

        states.put(chatId, st);

        log.info("[FIBO_GRID] ▶ START chatId={} symbol={} levels={} stepPct={}",
                chatId, st.symbol, levels, fmtBd(cfg.getDistancePct()));

        safeLive(() -> live.pushState(chatId, StrategyType.FIBONACCI_GRID, st.symbol, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_GRID, st.symbol, null, Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        String sym = st.symbol;

        if (sym != null) {
            safeLive(() -> live.clearTpSl(chatId, StrategyType.FIBONACCI_GRID, sym));
            safeLive(() -> live.clearPriceLines(chatId, StrategyType.FIBONACCI_GRID, sym));
            safeLive(() -> live.pushState(chatId, StrategyType.FIBONACCI_GRID, sym, false));
        }

        log.info("[FIBO_GRID] ⏹ STOP chatId={} symbol={} ticks={} buys={} sells={} inPos={}",
                chatId, sym, st.ticks, st.buys, st.sells, st.inPosition);
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
        safeLive(() -> live.pushPriceTick(chatId, StrategyType.FIBONACCI_GRID, symFinal, price, time));

        synchronized (st) {

            refreshSettingsIfNeeded(chatId, st, time);

            StrategySettings ss = st.ss;
            FibonacciGridStrategySettings cfg = st.cfg;

            if (symFinal == null) {
                pushHoldThrottled(chatId, null, st, "no_symbol", time);
                return;
            }
            if (cfg == null) {
                pushHoldThrottled(chatId, symFinal, st, "no_fibo_grid_settings", time);
                return;
            }

            int levels = nz(cfg.getGridLevels(), 6);
            if (levels < 1) {
                pushHoldThrottled(chatId, symFinal, st, "levels<1", time);
                return;
            }

            BigDecimal stepPct = nzBd(cfg.getDistancePct(), new BigDecimal("0.5"));
            if (stepPct.signum() <= 0) {
                pushHoldThrottled(chatId, symFinal, st, "step_pct<=0", time);
                return;
            }

            // лог раз в N тиков
            if (st.ticks % LOG_EVERY_TICKS == 0) {
                log.info("[FIBO_GRID] tick chatId={} sym={} price={} base={} inPos={}",
                        chatId, symFinal, fmtBd(price), fmtBd(st.basePrice), st.inPosition);
            }

            // 0) Инициализация базовой цены
            if (st.basePrice == null) {
                st.basePrice = price;
                ensureLevelArraySize(st, levels);

                safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_GRID, symFinal, null,
                        Signal.hold("base_price_set")));

                log.info("[FIBO_GRID] 🎯 base price set chatId={} sym={} base={}",
                        chatId, symFinal, fmtBd(st.basePrice));
            }

            // 1) EXIT по TP/SL (кумулятивно)
            if (st.inPosition && st.entryQty != null && st.tp != null && st.sl != null) {
                try {
                    var ex = tradeExecutionService.executeExitIfHit(
                            chatId,
                            StrategyType.FIBONACCI_GRID,
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

                        log.info("[FIBO_GRID] ✅ EXIT OK chatId={} sym={} price={} (tp={} sl={})",
                                chatId, symFinal, fmtBd(price), fmtBd(st.tp), fmtBd(st.sl));

                        clearPosition(st);

                        safeLive(() -> live.clearTpSl(chatId, StrategyType.FIBONACCI_GRID, symFinal));
                        safeLive(() -> live.clearPriceLines(chatId, StrategyType.FIBONACCI_GRID, symFinal));
                        safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_GRID, symFinal, null,
                                Signal.sell(1.0, "tp_sl_exit")));
                        return;
                    }
                } catch (Exception e) {
                    log.error("[FIBO_GRID] ❌ EXIT failed chatId={} err={}", chatId, e.getMessage(), e);
                }
            }

            // 2) Проверяем, не пришли ли на один из уровней покупки
            ensureLevelArraySize(st, levels);

            int hitLevel = findHitLevel(st.basePrice, price, stepPct, levels, st.levelFired);
            if (hitLevel < 0) {
                pushHoldThrottled(chatId, symFinal, st, "no_level_hit", time);
                return;
            }

            // 3) BUY на уровне
            try {
                // score: чем глубже уровень — тем выше уверенность (условно)
                double score = Math.min(100.0, 50.0 + hitLevel * 8.0);
                final double scoreFinal = score; // важно: для лямбды должно быть final

                // отмечаем уровень как “занятый” ДО покупки, чтобы не спамить повторными входами на одном тике
                st.levelFired[hitLevel] = true;

                var res = tradeExecutionService.executeEntry(
                        chatId,
                        StrategyType.FIBONACCI_GRID,
                        symFinal,
                        price,
                        BigDecimal.valueOf(scoreFinal / 100.0),
                        time,
                        ss
                );

                if (!res.executed()) {
                    // если вход заблокирован — откатываем fired, чтобы позже можно было снова попытаться
                    st.levelFired[hitLevel] = false;

                    log.info("[FIBO_GRID] ✋ BUY blocked chatId={} reason={}", chatId, res.reason());
                    pushHoldThrottled(chatId, symFinal, st, res.reason(), time);
                    return;
                }

                st.buys++;

                // позиция: накапливаем
                applyEntry(st, res.entryPrice(), res.qty(), res.tp(), res.sl());

                safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_GRID, symFinal, null,
                        Signal.buy(scoreFinal, "level_" + hitLevel)));

                log.info("[FIBO_GRID] 🟢 BUY level={} chatId={} sym={} price={} qty={} avgEntry={} tp={} sl={}",
                        hitLevel,
                        chatId,
                        symFinal,
                        fmtBd(price),
                        fmtBd(res.qty()),
                        fmtBd(st.entryPrice),
                        fmtBd(st.tp),
                        fmtBd(st.sl));
                return;

            } catch (Exception e) {
                // в случае ошибки — даём шанс уровню сработать позже
                st.levelFired[hitLevel] = false;

                log.error("[FIBO_GRID] ❌ BUY failed chatId={} err={}", chatId, e.getMessage(), e);
                pushHoldThrottled(chatId, symFinal, st, "buy_failed", time);
                return;
            }
        }
    }

    private void applyEntry(LocalState st, BigDecimal entryPrice, BigDecimal qty, BigDecimal tp, BigDecimal sl) {
        if (qty == null || qty.signum() <= 0) return;

        if (!st.inPosition || st.entryQty == null || st.entryQty.signum() <= 0 || st.entryPrice == null) {
            st.inPosition = true;
            st.entryQty = qty;
            st.entryPrice = entryPrice;
        } else {
            // средняя цена = (p1*q1 + p2*q2) / (q1+q2)
            BigDecimal q1 = st.entryQty;
            BigDecimal p1 = st.entryPrice;

            BigDecimal q2 = qty;
            BigDecimal p2 = entryPrice;

            BigDecimal sumQty = q1.add(q2);
            BigDecimal avg = p1.multiply(q1).add(p2.multiply(q2))
                    .divide(sumQty, 18, RoundingMode.HALF_UP);

            st.entryQty = sumQty;
            st.entryPrice = avg;
        }

        // TP/SL берём из TradeExecutionService результата (как у DCA)
        st.tp = tp;
        st.sl = sl;
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
            FibonacciGridStrategySettings cfg = fiboSettingsService.getOrCreate(chatId);

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

                int levels = nz(cfg.getGridLevels(), 6);
                ensureLevelArraySize(st, levels);

                log.info("[FIBO_GRID] ⚙️ settings updated chatId={} symbol={} levels={} stepPct={}",
                        chatId,
                        st.symbol,
                        levels,
                        fmtBd(cfg.getDistancePct())
                );

                String newSymbol = safeUpper(st.symbol);
                if (oldSymbol != null && newSymbol != null && !oldSymbol.equals(newSymbol)) {
                    // сменили символ — сбрасываем сетку/позицию
                    clearPosition(st);
                    st.basePrice = null;
                    st.levelFired = new boolean[Math.max(1, levels)];
                    st.lastHoldReason = null;
                }
            }

        } catch (Exception e) {
            st.lastSettingsLoadAt = now;
            log.warn("[FIBO_GRID] ⚠ settings refresh failed chatId={} msg={}", chatId, e.toString());
        }
    }

    private void ensureLevelArraySize(LocalState st, int levels) {
        if (st.levelFired == null || st.levelFired.length != Math.max(1, levels)) {
            st.levelFired = new boolean[Math.max(1, levels)];
        }
    }

    private String buildFingerprint(StrategySettings ss, FibonacciGridStrategySettings cfg) {
        String symbol = ss != null ? safeUpper(ss.getSymbol()) : "null";
        String ex     = ss != null ? String.valueOf(ss.getExchangeName()) : "null";
        String net    = ss != null ? String.valueOf(ss.getNetworkType()) : "null";
        String tf     = ss != null ? safe(ss.getTimeframe()) : "null";
        String candles = ss != null && ss.getCachedCandlesLimit() != null ? String.valueOf(ss.getCachedCandlesLimit()) : "null";

        String levels  = cfg != null ? String.valueOf(cfg.getGridLevels()) : "null";
        String stepPct = cfg != null ? String.valueOf(cfg.getDistancePct()) : "null";
        String vol     = cfg != null ? String.valueOf(cfg.getOrderVolume()) : "null";

        return symbol + "|" + ex + "|" + net + "|" + tf + "|" + candles + "|" + levels + "|" + stepPct + "|" + vol;
    }

    // =====================================================
    // LOAD StrategySettings(type=FIBONACCI_GRID)
    // =====================================================

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId)
                .stream()
                .filter(s -> s.getType() == StrategyType.FIBONACCI_GRID)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                                .thenComparing(StrategySettings::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings для FIBONACCI_GRID не найдены (chatId=" + chatId + ")"
                ));
    }

    // =====================================================
    // GRID LOGIC
    // =====================================================

    /**
     * Возвращает индекс уровня [0..levels-1], который сейчас “пробит” вниз.
     * Уровни считаются так:
     *   L0 = base * (1 - step%)
     *   L1 = base * (1 - 2*step%)
     *   ...
     */
    private int findHitLevel(BigDecimal base, BigDecimal price, BigDecimal stepPct, int levels, boolean[] fired) {
        if (base == null || price == null) return -1;

        BigDecimal step = stepPct.divide(new BigDecimal("100"), 18, RoundingMode.HALF_UP);

        for (int i = 0; i < levels; i++) {
            if (fired != null && i < fired.length && fired[i]) continue;

            BigDecimal mul = BigDecimal.ONE.subtract(step.multiply(BigDecimal.valueOf(i + 1L)));
            BigDecimal lvl = base.multiply(mul);

            if (price.compareTo(lvl) <= 0) {
                return i;
            }
        }
        return -1;
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

        safeLive(() -> live.pushSignal(chatId, StrategyType.FIBONACCI_GRID, symbol, null, Signal.hold(reason)));
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

    private static BigDecimal nzBd(BigDecimal v, BigDecimal def) {
        return v != null ? v : def;
    }

    private static String fmtBd(BigDecimal v) {
        if (v == null) return "null";
        return v.stripTrailingZeros().toPlainString();
    }
}
