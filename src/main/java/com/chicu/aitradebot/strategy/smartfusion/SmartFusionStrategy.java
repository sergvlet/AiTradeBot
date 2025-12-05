package com.chicu.aitradebot.strategy.smartfusion;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.core.ContextAwareStrategy;
import com.chicu.aitradebot.strategy.core.RuntimeIntrospectable;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionOrderExecutor;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionPnLTracker;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@StrategyBinding(StrategyType.SMART_FUSION)
@RequiredArgsConstructor
@Slf4j
public class SmartFusionStrategy implements TradingStrategy, RuntimeIntrospectable, ContextAwareStrategy {

    private final CandleProvider candleProvider;
    private final SmartFusionOrderExecutor orderExecutor;
    private final SmartFusionPnLTracker pnlTracker;
    private final SmartFusionStrategySettingsService settingsService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private long chatId;
    private String symbol;
    private String exchange;
    private NetworkType network;

    private SmartFusionStrategySettings cfg;

    @Getter
    private String lastEvent = "INIT";
    private Instant startedAt;

    // =====================================================================
    // INIT
    // =====================================================================

    @PostConstruct
    public void onInit() {
        log.info("🚀 SmartFusionStrategy bean loaded");
    }

    // =====================================================================
    // CONTEXT
    // =====================================================================

    @Override
    public void setContext(long chatId, String symbol) {
        this.chatId = chatId;
        this.symbol = symbol.toUpperCase(Locale.ROOT);

        loadSettings();

        log.info("⚙️ SmartFusion context applied → chatId={}, symbol={}, exchange={}, network={}",
                chatId, symbol, exchange, network);
    }

    private void loadSettings() {
        this.cfg = settingsService.getOrCreate(chatId);

        this.exchange = cfg.getExchange();
        this.network = cfg.getNetworkType();
    }

    // =====================================================================
    // TRAINING
    // =====================================================================

    private void train() {

        log.info("📚 SmartFusion TRAIN started (chatId={}, symbol={})", chatId, symbol);

        List<CandleProvider.Candle> candles =
                candleProvider.getRecentCandles(chatId, symbol, cfg.getTimeframe(), cfg.getCandleLimit());

        if (candles.size() < 50) {
            log.warn("⚠️ Training skipped — недостаточно свечей");
            return;
        }

        // Здесь может быть:
        // AI warm-up, ATR calibration, volatility threshold calibration

        log.info("📘 SmartFusion TRAIN complete");
    }

    // =====================================================================
    // START / STOP
    // =====================================================================

    @Override
    public synchronized void start() {

        if (running.get()) {
            log.warn("⚠️ SmartFusion already running");
            return;
        }

        loadSettings();
        train();

        running.set(true);
        startedAt = Instant.now();

        log.info("▶️ SmartFusion STARTED (symbol={}, tf={}, limit={})",
                symbol, cfg.getTimeframe(), cfg.getCandleLimit());
    }

    @Override
    public synchronized void stop() {
        if (!running.get()) {
            log.warn("⚠️ SmartFusion already stopped");
            return;
        }

        running.set(false);
        log.info("⏹ SmartFusion STOPPED {}", symbol);
    }

    @Override
    public boolean isActive() {
        return running.get();
    }

    // =====================================================================
    // EVENT-DRIVEN
    // =====================================================================

    @Override
    public void onPriceUpdate(String s, BigDecimal price) {
        if (!running.get()) return;

        try {
            executeCycle();
        } catch (Exception e) {
            log.error("❌ SmartFusion cycle error: {}", e.getMessage(), e);
        }
    }

    // =====================================================================
    // MAIN CYCLE
    // =====================================================================

    private void executeCycle() {

        loadSettings(); // настройки могли измениться из UI

        List<CandleProvider.Candle> candles =
                candleProvider.getRecentCandles(chatId, symbol, cfg.getTimeframe(), cfg.getCandleLimit());

        if (candles.size() < 30) return;

        double[] closes = candles.stream().mapToDouble(CandleProvider.Candle::close).toArray();
        double last = closes[closes.length - 1];

        double emaFast = ema(closes, cfg.getEmaFastPeriod());
        double emaSlow = ema(closes, cfg.getEmaSlowPeriod());
        double rsi = rsi(closes, cfg.getRsiPeriod());
        double[] bb = bollinger(closes, cfg.getBollingerPeriod(), cfg.getBollingerK());

        boolean buySignal = rsi < cfg.getRsiBuyThreshold() && emaFast > emaSlow;
        boolean sellSignal = rsi > cfg.getRsiSellThreshold() && emaFast < emaSlow;

        pnlTracker.updateIndicators(chatId, symbol, Map.of(
                "emaFast", emaFast,
                "emaSlow", emaSlow,
                "rsi", rsi,
                "bbUpper", bb[1],
                "bbLower", bb[2]
        ));

        if (buySignal) {
            processTrade(OrderSide.BUY, last);
        } else if (sellSignal) {
            processTrade(OrderSide.SELL, last);
        } else {
            lastEvent = "HOLD";
        }
    }

    // =====================================================================
    // TRADE EXECUTION
    // =====================================================================

    private void processTrade(OrderSide side, double price) {
        try {

            double qty = cfg.getCapitalUsd() / price;

            orderExecutor.placeMarketOrder(
                    chatId,
                    symbol,
                    cfg.getNetworkType(),
                    cfg.getExchange(),
                    side,
                    BigDecimal.valueOf(qty)
            );

            pnlTracker.recordTrade(chatId, symbol, price, qty, true, 1.0);

            lastEvent = side.name();

            log.info("💱 SmartFusion {} executed @ {}", side, price);

        } catch (Exception e) {
            log.error("❌ Trade error: {}", e.getMessage(), e);
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
        double gain = 0, loss = 0;
        for (int i = arr.length - p; i < arr.length; i++) {
            double d = arr[i] - arr[i - 1];
            if (d > 0) gain += d; else loss -= d;
        }
        double rs = gain / Math.max(1e-9, loss);
        return 100 - 100 / (1 + rs);
    }

    private double[] bollinger(double[] arr, int period, double k) {
        if (arr.length < period) return new double[]{0, 0, 0};

        double mean = Arrays.stream(arr, arr.length - period, arr.length).average().orElse(0.0);
        double variance = Arrays.stream(arr, arr.length - period, arr.length)
                                  .map(v -> (v - mean) * (v - mean))
                                  .sum() / period;

        double std = Math.sqrt(variance);

        return new double[]{mean, mean + k * std, mean - k * std};
    }

    // =====================================================================
    // RUNTIME
    // =====================================================================

    @Override
    public String getSymbol() {
        return symbol;
    }

    @Override
    public Instant getStartedAt() {
        return startedAt;
    }

    @Override
    public String getThreadName() {
        return "SMARTFUSION-" + symbol;
    }
}
