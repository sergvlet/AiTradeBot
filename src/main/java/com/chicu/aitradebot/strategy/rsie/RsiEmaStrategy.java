package com.chicu.aitradebot.strategy.rsie;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.core.ContextAwareStrategy;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Простейшая RSI+EMA стратегия v4:
 * - работает через CandleProvider
 * - поддерживает setContext(chatId, symbol)
 * - тренируется перед стартом (train())
 * - вызывается StrategyEngine через onPriceUpdate(...)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.RSI_EMA)
public class RsiEmaStrategy implements TradingStrategy, ContextAwareStrategy {

    private final CandleProvider candleProvider;

    private long chatId;
    private String symbol;

    private final AtomicBoolean active = new AtomicBoolean(false);

    // Параметры индикаторов (потом можно вынести в таблицу настроек)
    private int rsiPeriod = 14;
    private int emaFast = 9;
    private int emaSlow = 21;

    // Таймфрейм и глубина истории
    private String timeframe = "1m";
    private int candleLimit = 200;

    // =====================================================================
    // CONTEXT
    // =====================================================================

    @Override
    public void setContext(long chatId, String symbol) {
        this.chatId = chatId;
        this.symbol = symbol.toUpperCase();

        log.info("⚙️ RSI/EMA context set: chatId={}, symbol={}", chatId, this.symbol);
    }

    // =====================================================================
    // TRAIN
    // =====================================================================

    /**
     * "Обучение" / калибровка перед стартом.
     * Сейчас — просто прогрев индикаторов и проверка, что свечей достаточно.
     */
    private void train() {
        log.info("📚 RSI/EMA TRAIN start (chatId={}, symbol={})", chatId, symbol);

        List<CandleProvider.Candle> candles =
                candleProvider.getRecentCandles(chatId, symbol, timeframe, candleLimit);

        if (candles.size() < emaSlow + 5) {
            log.warn("⚠️ RSI/EMA TRAIN: мало данных ({} свечей, нужно ≥ {})",
                    candles.size(), emaSlow + 5);
            return;
        }

        double[] closes = candles.stream()
                .mapToDouble(CandleProvider.Candle::close)
                .toArray();

        // просто считаем индикаторы чтобы убедиться, что всё ок
        double emaF = ema(closes, emaFast);
        double emaS = ema(closes, emaSlow);
        double rsi = rsi(closes, rsiPeriod);

        log.info("📘 RSI/EMA TRAIN done: EMA_fast={} EMA_slow={} RSI={}",
                String.format("%.4f", emaF),
                String.format("%.4f", emaS),
                String.format("%.2f", rsi));
    }

    // =====================================================================
    // START / STOP
    // =====================================================================

    @Override
    public synchronized void start() {
        if (active.get()) {
            log.warn("⚠️ RSI/EMA already started (symbol={})", symbol);
            return;
        }

        train();

        active.set(true);
        log.info("▶️ RSI+EMA started {}", symbol);
    }

    @Override
    public synchronized void stop() {
        if (!active.get()) {
            log.warn("⚠️ RSI/EMA already stopped (symbol={})", symbol);
            return;
        }

        active.set(false);
        log.info("⏹ RSI+EMA stopped {}", symbol);
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    // =====================================================================
    // EVENT-DRIVEN
    // =====================================================================

    @Override
    public void onPriceUpdate(String symbolIgnored, BigDecimal priceIgnored) {
        if (!active.get()) return;

        try {
            executeCycle();
        } catch (Exception e) {
            log.error("❌ RSI/EMA cycle error: {}", e.getMessage(), e);
        }
    }

    // =====================================================================
    // MAIN CYCLE
    // =====================================================================

    private void executeCycle() {

        List<CandleProvider.Candle> candles =
                candleProvider.getRecentCandles(chatId, symbol, timeframe, candleLimit);

        if (candles.size() < emaSlow + 5) {
            return;
        }

        double[] closes = candles.stream()
                .mapToDouble(CandleProvider.Candle::close)
                .toArray();

        double emaF = ema(closes, emaFast);
        double emaS = ema(closes, emaSlow);
        double rsi = rsi(closes, rsiPeriod);

        double last = closes[closes.length - 1];

        if (rsi < 30 && emaF > emaS) {
            log.info("📈 RSI+EMA BUY signal {} price={} RSI={} EMAf={} EMAs={}",
                    symbol,
                    last,
                    String.format("%.2f", rsi),
                    String.format("%.4f", emaF),
                    String.format("%.4f", emaS));
        } else if (rsi > 70 && emaF < emaS) {
            log.info("📉 RSI+EMA SELL signal {} price={} RSI={} EMAf={} EMAs={}",
                    symbol,
                    last,
                    String.format("%.2f", rsi),
                    String.format("%.4f", emaF),
                    String.format("%.4f", emaS));
        }
    }

    // =====================================================================
    // INDICATORS
    // =====================================================================

    private double ema(double[] arr, int p) {
        double k = 2.0 / (p + 1);
        double v = arr[0];
        for (int i = 1; i < arr.length; i++) {
            v = arr[i] * k + v * (1 - k);
        }
        return v;
    }

    private double rsi(double[] arr, int p) {
        if (arr.length < p + 1) return 50.0;

        double gain = 0.0;
        double loss = 0.0;

        for (int i = arr.length - p; i < arr.length; i++) {
            double diff = arr[i] - arr[i - 1];
            if (diff > 0) {
                gain += diff;
            } else {
                loss -= diff; // diff < 0 → -diff > 0
            }
        }

        if (loss == 0.0) {
            return 100.0;
        }

        double rs = gain / loss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
}
