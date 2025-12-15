package com.chicu.aitradebot.strategy.smartfusion;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.common.util.TimeframeUtils;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.core.ContextAwareStrategy;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.live.LiveCandleAggregator;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionOrderExecutor;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionPnLTracker;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.SMART_FUSION)
public class SmartFusionStrategy implements TradingStrategy, ContextAwareStrategy {

    private final CandleProvider candleProvider;
    private final SmartFusionOrderExecutor orderExecutor;
    private final SmartFusionPnLTracker pnlTracker;
    private final SmartFusionStrategySettingsService settingsService;
    private final StrategyLivePublisher live;
    private final LiveCandleAggregator candleAggregator;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile long chatId;
    private volatile String symbol;
    private volatile String exchange;
    private volatile NetworkType network;
    private volatile SmartFusionStrategySettings cfg;

    @Getter
    private volatile String lastEvent = "INIT";

    private volatile Instant startedAt;

    private volatile BigDecimal lastEmaFast;
    private volatile BigDecimal lastEmaSlow;
    private volatile BigDecimal lastSupport;
    private volatile BigDecimal lastResistance;

    @PostConstruct
    public void onInit() {
        log.info("🚀 SmartFusionStrategy bean loaded");
    }

    // =========================================================
    // CONTEXT
    // =========================================================
    @Override
    public void setContext(long chatId, String symbol) {
        this.chatId = chatId;
        if (symbol != null && !symbol.isBlank()) {
            this.symbol = symbol.toUpperCase(Locale.ROOT);
        }
        loadSettings();
    }

    private void loadSettings() {
        SmartFusionStrategySettings s = settingsService.getOrCreate(chatId);
        this.cfg = s;
        this.exchange = s.getExchange();
        this.network = s.getNetworkType();

        if (this.symbol == null || this.symbol.isBlank()) {
            this.symbol = (s.getSymbol() != null && !s.getSymbol().isBlank())
                    ? s.getSymbol().toUpperCase(Locale.ROOT)
                    : "BTCUSDT";
        }
    }

    // =========================================================
    // START / STOP
    // =========================================================
    @Override
    public synchronized void start(Long chatId, String symbol) {

        if (running.get()) {
            log.warn("⚠️ SMART_FUSION already running chatId={} symbol={}", this.chatId, this.symbol);
            return;
        }

        this.chatId = chatId;

        if (symbol != null && !symbol.isBlank()) {
            this.symbol = symbol.toUpperCase(Locale.ROOT);
        }

        // ✅ ЕДИНСТВЕННО КОРРЕКТНЫЙ СПОСОБ ПОЛУЧИТЬ НАСТРОЙКИ
        loadSettings();

        running.set(true);
        startedAt = Instant.now();

        // сброс дедупов для UI
        lastEmaFast = null;
        lastEmaSlow = null;
        lastSupport = null;
        lastResistance = null;

        // уведомляем UI
        live.pushState(
                this.chatId,
                StrategyType.SMART_FUSION,
                this.symbol,
                true
        );

        log.info("▶️ SMART_FUSION START chatId={} symbol={}", this.chatId, this.symbol);
    }

    @Override
    public synchronized void stop(Long chatId, String ignored) {
        if (!running.get()) return;
        running.set(false);

        long tfMillis = TimeframeUtils.toMillis(cfg.getTimeframe());
        candleAggregator.flush(chatId, StrategyType.SMART_FUSION, symbol, cfg.getTimeframe(), tfMillis);

        live.pushState(chatId, StrategyType.SMART_FUSION, symbol, false);
        log.info("⏹ SMART_FUSION STOP chatId={} symbol={}", chatId, symbol);
    }

    // =========================================================
    // INFO
    // =========================================================
    @Override public boolean isActive(Long chatId) { return running.get(); }
    @Override public Instant getStartedAt(Long chatId) { return startedAt; }
    @Override public String getThreadName(Long chatId) { return "SMARTFUSION-" + symbol; }

    // =========================================================
    // PRICE UPDATE
    // =========================================================
    @Override
    public void onPriceUpdate(Long chatId, String symbol, BigDecimal price, Instant ts) {

        log.debug(
                "🧠 SMART_FUSION onPriceUpdate running={} chatId={} symbol={}",
                running.get(),
                this.chatId,
                this.symbol
        );

        if (price == null) return;

        Instant time = ts != null ? ts : Instant.now();

        // =====================================================
        // 🔥 ВСЕГДА пушим цену (визуализация)
        // =====================================================
        live.pushPriceTick(
                this.chatId,
                StrategyType.SMART_FUSION,
                this.symbol,
                price,
                time
        );

        // =====================================================
        // 🟣 ЕСЛИ СТРАТЕГИЯ НЕ ЗАПУЩЕНА — ТОЛЬКО ВИЗУАЛ
        // =====================================================
        if (!running.get()) {

            // fallback уровни ±0.2%
            BigDecimal priceBd = price;
            BigDecimal delta =
                    priceBd.multiply(BigDecimal.valueOf(0.002))
                            .setScale(8, RoundingMode.HALF_UP);

            live.pushLevels(
                    this.chatId,
                    StrategyType.SMART_FUSION,
                    this.symbol,
                    List.of(
                            scale(priceBd.subtract(delta)),
                            scale(priceBd.add(delta))
                    )
            );

            return;
        }

        // =====================================================
        // ✅ СТРАТЕГИЯ АКТИВНА — ПОЛНЫЙ ЦИКЛ
        // =====================================================
        long tfMillis = TimeframeUtils.toMillis(cfg.getTimeframe());

        candleAggregator.onPriceTick(
                this.chatId,
                StrategyType.SMART_FUSION,
                this.symbol,
                cfg.getTimeframe(),
                tfMillis,
                price,
                time
        );

        executeCycle(price.doubleValue(), cfg);
    }

    // =========================================================
    // MAIN CYCLE
    // =========================================================
    private void executeCycle(double lastPrice, SmartFusionStrategySettings cfg) {

        List<CandleProvider.Candle> candles =
                candleProvider.getRecentCandles(
                        chatId,
                        symbol,
                        cfg.getTimeframe(),
                        cfg.getCandleLimit()
                );

        BigDecimal priceBd = BigDecimal.valueOf(lastPrice);

        // =====================================================
        // ⏳ FALLBACK — пока не хватает свечей
        // =====================================================
        int need = Math.max(
                Math.max(cfg.getEmaSlowPeriod(), cfg.getAtrPeriod()),
                cfg.getRsiPeriod()
        ) + 5;

        if (candles == null || candles.size() < need) {

            // минимальный псевдо-ATR (0.2%)
            BigDecimal fallbackAtr =
                    priceBd.multiply(BigDecimal.valueOf(0.002))
                            .setScale(8, RoundingMode.HALF_UP);

            BigDecimal support    = priceBd.subtract(fallbackAtr);
            BigDecimal resistance = priceBd.add(fallbackAtr);

            // уровни сразу в UI
            live.pushLevels(
                    chatId,
                    StrategyType.SMART_FUSION,
                    symbol,
                    List.of(support, resistance)
            );

            // базовая зона
            live.pushZone(
                    chatId,
                    StrategyType.SMART_FUSION,
                    symbol,
                    resistance,
                    support
            );

            log.debug(
                    "⏳ SMART_FUSION waiting candles: have={} need={}",
                    candles == null ? 0 : candles.size(),
                    need
            );

            return;
        }

        // =====================================================
        // ✅ НОРМАЛЬНЫЙ РАСЧЁТ (когда свечей достаточно)
        // =====================================================
        double[] closes = candles.stream()
                .mapToDouble(CandleProvider.Candle::close)
                .toArray();

        double emaFast = ema(closes, cfg.getEmaFastPeriod());
        double emaSlow = ema(closes, cfg.getEmaSlowPeriod());
        double rsi     = rsi(closes, cfg.getRsiPeriod());
        double atr     = calcAtr(closes, cfg.getAtrPeriod());

        BigDecimal atrBd = BigDecimal.valueOf(atr).setScale(8, RoundingMode.HALF_UP);

        // =====================================================
        // 📈 EMA
        // =====================================================
        pushPriceLineDedup("EMA_FAST", emaFast, true);
        pushPriceLineDedup("EMA_SLOW", emaSlow, false);

        // =====================================================
        // 🟣 SMART LEVELS (ATR-based)
        // =====================================================
        BigDecimal support    = priceBd.subtract(atrBd.multiply(BigDecimal.valueOf(1.5)));
        BigDecimal resistance = priceBd.add(atrBd.multiply(BigDecimal.valueOf(1.5)));

        if (!support.equals(lastSupport) || !resistance.equals(lastResistance)) {

            live.pushLevels(
                    chatId,
                    StrategyType.SMART_FUSION,
                    symbol,
                    List.of(scale(support), scale(resistance))
            );

            lastSupport = support;
            lastResistance = resistance;
        }

        // =====================================================
        // 🟠 ZONE
        // =====================================================
        live.pushZone(
                chatId,
                StrategyType.SMART_FUSION,
                symbol,
                resistance,
                support
        );

        // =====================================================
        // 📊 ATR
        // =====================================================
        live.pushAtr(
                chatId,
                StrategyType.SMART_FUSION,
                symbol,
                atr,
                atr / lastPrice * 100
        );

        // =====================================================
        // 🔴🟢 SIGNALS / TRADES
        // =====================================================
        if (rsi < cfg.getRsiBuyThreshold() && emaFast > emaSlow) {

            live.pushSignal(
                    chatId,
                    StrategyType.SMART_FUSION,
                    symbol,
                    "BUY",
                    1.0
            );

            live.pushTradeZone(
                    chatId,
                    StrategyType.SMART_FUSION,
                    symbol,
                    "BUY",
                    priceBd.add(atrBd),
                    priceBd.subtract(atrBd)
            );

            processTrade(OrderSide.BUY, lastPrice, cfg);
        }

        if (rsi > cfg.getRsiSellThreshold() && emaFast < emaSlow) {

            live.pushSignal(
                    chatId,
                    StrategyType.SMART_FUSION,
                    symbol,
                    "SELL",
                    1.0
            );

            live.pushTradeZone(
                    chatId,
                    StrategyType.SMART_FUSION,
                    symbol,
                    "SELL",
                    priceBd.add(atrBd),
                    priceBd.subtract(atrBd)
            );

            processTrade(OrderSide.SELL, lastPrice, cfg);
        }

        // =====================================================
        // 🎯 TP / SL
        // =====================================================
        live.pushTpSl(
                chatId,
                StrategyType.SMART_FUSION,
                symbol,
                priceBd.add(atrBd.multiply(BigDecimal.valueOf(2))),
                priceBd.subtract(atrBd.multiply(BigDecimal.valueOf(2)))
        );

        lastEvent = String.format(Locale.ROOT, "RSI=%.2f ATR=%.2f", rsi, atr);
    }

    // =========================================================
    // REPLAY
    // =========================================================
    @Override
    public void replayLayers(Long chatId) {
        if (!running.get()) return;

        List<CandleProvider.Candle> candles =
                candleProvider.getRecentCandles(chatId, symbol, cfg.getTimeframe(), cfg.getCandleLimit());

        if (candles == null || candles.isEmpty()) return;

        double last = candles.get(candles.size() - 1).close();
        executeCycle(last, cfg);

        log.debug("🔁 SMART_FUSION replayLayers complete chatId={} symbol={}", chatId, symbol);
    }

    // =========================================================
    // HELPERS
    // =========================================================
    private void pushPriceLineDedup(String name, double value, boolean fast) {
        BigDecimal bd = scale(BigDecimal.valueOf(value));
        if (fast) {
            if (lastEmaFast == null || bd.compareTo(lastEmaFast) != 0) {
                live.pushPriceLine(chatId, StrategyType.SMART_FUSION, symbol, name, bd);
                lastEmaFast = bd;
            }
        } else {
            if (lastEmaSlow == null || bd.compareTo(lastEmaSlow) != 0) {
                live.pushPriceLine(chatId, StrategyType.SMART_FUSION, symbol, name, bd);
                lastEmaSlow = bd;
            }
        }
    }

    private BigDecimal scale(BigDecimal v) {
        return v.setScale(8, RoundingMode.HALF_UP);
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
            if (d > 0) gain += d; else loss -= d;
        }
        double rs = gain / Math.max(1e-9, loss);
        return 100 - 100 / (1 + rs);
    }

    private double calcAtr(double[] closes, int period) {
        double sum = 0;
        for (int i = closes.length - period; i < closes.length; i++) {
            sum += Math.abs(closes[i] - closes[i - 1]);
        }
        return sum / period;
    }

    // =========================================================
// TRADE
// =========================================================
    private void processTrade(OrderSide side, double price, SmartFusionStrategySettings cfg) {

        double qty = cfg.getCapitalUsd() / price;

        try {
            orderExecutor.placeMarketOrder(
                    this.chatId,
                    this.symbol,
                    this.network,
                    this.exchange,
                    side,
                    BigDecimal.valueOf(qty)
            );

            live.pushTrade(
                    this.chatId,
                    StrategyType.SMART_FUSION,
                    this.symbol,
                    side.name(),
                    BigDecimal.valueOf(price),
                    BigDecimal.valueOf(qty),
                    Instant.now()
            );

        } catch (Exception e) {
            log.error(
                    "❌ SMART_FUSION trade failed chatId={} side={} price={}",
                    this.chatId,
                    side,
                    price,
                    e
            );
        }
    }

}
