package com.chicu.aitradebot.strategy.rsie;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.common.util.TimeframeUtils;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.core.ContextAwareStrategy;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.live.LiveCandleAggregator;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.RSI_EMA)
public class RsiEmaStrategy implements TradingStrategy, ContextAwareStrategy {

    private final CandleProvider candleProvider;
    private final StrategyLivePublisher live;
    private final LiveCandleAggregator candleAggregator;
    private final RsiEmaStrategySettingsService settingsService;

    // ============================================================
    // STATE
    // ============================================================
    private static class State {
        long chatId;
        String symbol;
        boolean active;
        Instant startedAt;

        BigDecimal entryPrice;
        String entrySide; // BUY / SELL

        BigDecimal lastEmaFast;
        BigDecimal lastEmaSlow;

        BigDecimal lastSentEntry;
        BigDecimal lastSentTp;
        BigDecimal lastSentSl;

        String lastSignalName;
        Double lastSignalConfidence;
    }

    private final Map<Long, State> states = new ConcurrentHashMap<>();

    // ============================================================
    // CONTEXT
    // ============================================================
    @Override
    public void setContext(long chatId, String symbol) {
        State st = states.computeIfAbsent(chatId, id -> new State());
        st.chatId = chatId;
        st.symbol = (symbol != null ? symbol : "BTCUSDT").toUpperCase(Locale.ROOT);
    }

    // ============================================================
    // START / STOP
    // ============================================================
    @Override
    public synchronized void start(Long chatId, String symbol) {

        RsiEmaStrategySettings cfg = settingsService.getOrCreate(chatId);

        State st = states.computeIfAbsent(chatId, id -> new State());
        st.chatId = chatId;
        st.symbol = (symbol != null && !symbol.isBlank())
                ? symbol.toUpperCase(Locale.ROOT)
                : cfg.getSymbol().toUpperCase(Locale.ROOT);

        st.active = true;
        st.startedAt = Instant.now();

        // 🔧 FIX: полный сброс UI-состояния
        st.entryPrice = null;
        st.entrySide = null;
        st.lastEmaFast = null;
        st.lastEmaSlow = null;
        st.lastSentEntry = null;
        st.lastSentTp = null;
        st.lastSentSl = null;
        st.lastSignalName = null;
        st.lastSignalConfidence = null;

        cfg.setActive(true);
        cfg.setSymbol(st.symbol);
        settingsService.save(cfg);

        live.pushState(chatId, StrategyType.RSI_EMA, st.symbol, true);
        log.info("▶️ RSI/EMA START chatId={} symbol={}", chatId, st.symbol);
    }

    @Override
    public synchronized void stop(Long chatId, String ignored) {

        State st = states.get(chatId);
        if (st == null) return;

        RsiEmaStrategySettings cfg = settingsService.getOrCreate(chatId);
        cfg.setActive(false);
        settingsService.save(cfg);

        st.active = false;

        long tfMillis = TimeframeUtils.toMillis(cfg.getTimeframe());
        candleAggregator.flush(
                chatId,
                StrategyType.RSI_EMA,
                st.symbol,
                cfg.getTimeframe(),
                tfMillis
        );

        live.pushState(chatId, StrategyType.RSI_EMA, st.symbol, false);
        log.info("⏹ RSI/EMA STOP chatId={} symbol={}", chatId, st.symbol);
    }

    // ============================================================
    // MAIN LOOP
    // ============================================================
    @Override
    public void onPriceUpdate(Long chatId, String symbol, BigDecimal price, Instant ts) {

        State st = states.get(chatId);
        if (st == null || !st.active || price == null) return;

        RsiEmaStrategySettings cfg = settingsService.getOrCreate(chatId);

        Instant time = ts != null ? ts : Instant.now();

        // 🔥 PRICE → UI
        live.pushPriceTick(chatId, StrategyType.RSI_EMA, st.symbol, price, time);

        long tfMillis = TimeframeUtils.toMillis(cfg.getTimeframe());
        candleAggregator.onPriceTick(
                chatId,
                StrategyType.RSI_EMA,
                st.symbol,
                cfg.getTimeframe(),
                tfMillis,
                price,
                time
        );

        executeCycle(st, cfg, price, time);
    }

    // ============================================================
    // STRATEGY CYCLE
    // ============================================================
    private void executeCycle(State st,
                              RsiEmaStrategySettings cfg,
                              BigDecimal lastPrice,
                              Instant time) {

        // ✅ ЧИТАЕМ ЧЕРЕЗ CandleProvider (он уже наполняется агрегатором)
        List<CandleProvider.Candle> candles =
                candleProvider.getRecentCandles(
                        st.chatId,
                        st.symbol,
                        cfg.getTimeframe(),
                        cfg.getCachedCandlesLimit()
                );

        if (candles == null || candles.isEmpty()) return;

        int need = Math.max(cfg.getEmaSlow(), cfg.getRsiPeriod()) + 2;
        if (candles.size() < need) return;

        double[] closes = candles.stream()
                .mapToDouble(CandleProvider.Candle::close)
                .toArray();

        BigDecimal emaFast = BigDecimal
                .valueOf(ema(closes, cfg.getEmaFast()))
                .setScale(8, RoundingMode.HALF_UP);

        BigDecimal emaSlow = BigDecimal
                .valueOf(ema(closes, cfg.getEmaSlow()))
                .setScale(8, RoundingMode.HALF_UP);

        double rsi = rsi(closes, cfg.getRsiPeriod());

        // =========================================================
        // EMA → UI (price_line)
        // =========================================================
        if (st.lastEmaFast == null || emaFast.compareTo(st.lastEmaFast) != 0) {
            live.pushPriceLine(
                    st.chatId,
                    StrategyType.RSI_EMA,
                    st.symbol,
                    "EMA_FAST",
                    emaFast
            );
            st.lastEmaFast = emaFast;
        }

        if (st.lastEmaSlow == null || emaSlow.compareTo(st.lastEmaSlow) != 0) {
            live.pushPriceLine(
                    st.chatId,
                    StrategyType.RSI_EMA,
                    st.symbol,
                    "EMA_SLOW",
                    emaSlow
            );
            st.lastEmaSlow = emaSlow;
        }

        // =========================================================
        // SIGNAL LOGIC
        // =========================================================
        boolean buy =
                rsi <= cfg.getRsiBuyThreshold()
                && emaFast.compareTo(emaSlow) > 0;

        boolean sell =
                rsi >= cfg.getRsiSellThreshold()
                && emaFast.compareTo(emaSlow) < 0;

        if (buy) {
            pushSignalDedup(
                    st,
                    "BUY",
                    confidenceFromRsi(rsi, cfg.getRsiBuyThreshold(), true)
            );
        }

        if (sell) {
            pushSignalDedup(
                    st,
                    "SELL",
                    confidenceFromRsi(rsi, cfg.getRsiSellThreshold(), false)
            );
        }
    }


    // ============================================================
    // REPLAY
    // ============================================================
    @Override
    public void replayLayers(Long chatId) {

        State st = states.get(chatId);
        if (st == null || !st.active) return;

        RsiEmaStrategySettings cfg = settingsService.getOrCreate(chatId);

        List<CandleProvider.Candle> candles =
                candleProvider.getRecentCandles(
                        st.chatId,
                        st.symbol,
                        cfg.getTimeframe(),
                        cfg.getCachedCandlesLimit()
                );

        if (candles == null || candles.isEmpty()) return;

        int need = Math.max(cfg.getEmaSlow(), cfg.getRsiPeriod()) + 2;
        if (candles.size() < need) return;

        double[] closes = candles.stream().mapToDouble(CandleProvider.Candle::close).toArray();

        BigDecimal emaFast = BigDecimal.valueOf(ema(closes, cfg.getEmaFast())).setScale(8, RoundingMode.HALF_UP);
        BigDecimal emaSlow = BigDecimal.valueOf(ema(closes, cfg.getEmaSlow())).setScale(8, RoundingMode.HALF_UP);

        live.pushPriceLine(chatId, StrategyType.RSI_EMA, st.symbol, "EMA_FAST", emaFast);
        live.pushPriceLine(chatId, StrategyType.RSI_EMA, st.symbol, "EMA_SLOW", emaSlow);

        st.lastEmaFast = emaFast;
        st.lastEmaSlow = emaSlow;

        log.debug("🔁 RSI/EMA replayLayers done chatId={} symbol={}", chatId, st.symbol);
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private void pushSignalDedup(State st, String name, double conf) {
        if (name.equals(st.lastSignalName)) return;
        live.pushSignal(st.chatId, StrategyType.RSI_EMA, st.symbol, name, conf);
        st.lastSignalName = name;
        st.lastSignalConfidence = conf;
    }

    private double ema(double[] arr, int p) {
        double k = 2.0 / (p + 1);
        double v = arr[0];
        for (int i = 1; i < arr.length; i++) v = arr[i] * k + v * (1 - k);
        return v;
    }

    private double rsi(double[] arr, int p) {
        double gain = 0, loss = 0;
        for (int i = arr.length - p; i < arr.length; i++) {
            double d = arr[i] - arr[i - 1];
            if (d > 0) gain += d;
            else loss -= d;
        }
        if (loss == 0) return 100;
        return 100 - (100 / (1 + gain / loss));
    }

    private double confidenceFromRsi(double rsi, double t, boolean buy) {
        double raw = buy ? (t - rsi) : (rsi - t);
        return Math.min(1, Math.max(0, raw / 30.0));
    }
    // ============================================================
// INFO (TradingStrategy contract)
// ============================================================
    @Override
    public boolean isActive(Long chatId) {
        State st = states.get(chatId);
        return st != null && st.active;
    }

    @Override
    public Instant getStartedAt(Long chatId) {
        State st = states.get(chatId);
        return st != null ? st.startedAt : null;
    }

    @Override
    public String getThreadName(Long chatId) {
        State st = states.get(chatId);
        return st == null
                ? "RSI_EMA-" + chatId
                : "RSI_EMA-" + chatId + "-" + st.symbol;
    }


}
