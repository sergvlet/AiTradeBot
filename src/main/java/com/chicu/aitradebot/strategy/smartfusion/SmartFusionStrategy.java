package com.chicu.aitradebot.strategy.smartfusion;

import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionOrderExecutor;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionPnLTracker;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.enums.OrderSide;

import com.chicu.aitradebot.market.MarketStreamManager;

import com.chicu.aitradebot.strategy.core.ContextAwareStrategy;
import com.chicu.aitradebot.strategy.core.RuntimeIntrospectable;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;

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

    private final SmartFusionCandleService candleService;
    private final SmartFusionOrderExecutor orderExecutor;
    private final SmartFusionPnLTracker pnlTracker;
    private final SmartFusionStrategySettingsService settingsService;

    private final MarketStreamManager marketStreamManager;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    private long chatId;
    private String symbol;
    private String exchange;
    private NetworkType network;

    @Getter
    private String lastEvent = "INIT";
    private Instant startedAt;
    private String threadName;

    @PostConstruct
    public void onInit() {
        log.info("🚀 SmartFusionStrategy инициализирована как Spring Bean!");
    }

    // =====================================================================
    // ▶️ START
    // =====================================================================
    @Override
    public synchronized void start() {

        if (running.get()) {
            log.warn("⚠️ SmartFusion уже запущена: chatId={}, symbol={}", chatId, symbol);
            return;
        }

        if (symbol == null) {
            log.error("❌ Невозможно запустить SmartFusion: symbol == null (chatId={})", chatId);
            return;
        }

        running.set(true);
        startedAt = Instant.now();
        threadName = "SmartFusion-" + symbol;

        log.info("▶️ Запуск SmartFusion: chatId={}, symbol={}, exchange={}, network={}",
                chatId, symbol, exchange, network);

        try {
            // --------------------------------------------------------
            // 🟢 ВАЖНО: единственный правильный вызов
            // MarketStreamManager сам подключает CandleAggregator
            // и BinanceWebSocketClient, передаёт тики в CandleAggregator
            // а тот → RealtimeStreamService → frontend WebSocket
            // --------------------------------------------------------
            log.info("📡 Запуск Binance realtime stream через MarketStreamManager.start({})", symbol);
            marketStreamManager.start(symbol);

        } catch (Exception e) {
            log.error("❌ Ошибка запуска realtime stream: {}", e.getMessage(), e);
        }

        // Запускаем рабочий поток SmartFusion
        workerThread = new Thread(this::runLoop, threadName);
        workerThread.setDaemon(true);
        workerThread.start();

        log.info("✅ SmartFusion поток успешно запущен: {}", threadName);
    }

    // =====================================================================
    // ⏹ STOP
    // =====================================================================
    @Override
    public synchronized void stop() {
        if (!running.get()) {
            log.warn("⚠️ SmartFusion уже остановлена (chatId={}, symbol={})", chatId, symbol);
            return;
        }

        log.info("⏹ Остановка SmartFusion: chatId={}, symbol={}", chatId, symbol);

        running.set(false);
        if (workerThread != null && workerThread.isAlive()) {
            workerThread.interrupt();
            try {
                workerThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("✅ SmartFusion успешно остановлена (chatId={}, symbol={})", chatId, symbol);
    }

    @Override
    public boolean isActive() {
        return running.get();
    }

    // =====================================================================
    // CALLBACK (если кто-то вызовет)
    // =====================================================================
    @Override
    public void onPriceUpdate(String symbol, double price) {
        log.debug("📈 onPriceUpdate: {} {}", symbol, price);
    }

    // =====================================================================
    // CONTEXT
    // =====================================================================
    @Override
    public void setContext(long chatId, String symbol) {
        SmartFusionStrategySettings cfg = settingsService.getOrCreate(chatId);

        cfg.setChatId(chatId);
        cfg.setSymbol(symbol.toUpperCase(Locale.ROOT));
        settingsService.save(cfg);

        this.chatId = chatId;
        this.symbol = symbol.toUpperCase(Locale.ROOT);
        this.exchange = cfg.getExchange();
        this.network = cfg.getNetworkType();

        log.info("⚙️ Контекст установлен: chatId={}, symbol={}, exchange={}, network={}",
                this.chatId, this.symbol, this.exchange, this.network);
    }

    // =====================================================================
    // MAIN LOOP
    // =====================================================================
    private void runLoop() {
        log.info("🔁 Старт цикла SmartFusion (thread={}) chatId={}, symbol={}",
                Thread.currentThread().getName(), chatId, symbol);

        try {
            while (running.get()) {
                long start = System.currentTimeMillis();

                try {
                    executeCycle();
                } catch (Exception e) {
                    log.error("❌ Ошибка executeCycle: {}", e.getMessage(), e);
                }

                long elapsed = System.currentTimeMillis() - start;
                log.debug("⏱ Цикл выполнен за {} ms (chatId={}, symbol={})", elapsed, chatId, symbol);

                Thread.sleep(10_000);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            running.set(false);
            log.info("🔚 SmartFusion цикл завершён (chatId={}, symbol={})", chatId, symbol);
        }
    }

    // =====================================================================
    // ONE CYCLE
    // =====================================================================
    private void executeCycle() {
        SmartFusionStrategySettings cfg = settingsService.getOrCreate(chatId);

        List<CandleProvider.Candle> candles = candleService.getCandles(cfg);

        if (candles.isEmpty()) {
            log.warn("⚠️ Нет свечей для анализа ({})", symbol);
            return;
        }

        if (candles.size() < 20) {
            log.debug("ℹ️ Недостаточно свечей: {}", candles.size());
            return;
        }

        double[] closes = candles.stream()
                .mapToDouble(CandleProvider.Candle::close)
                .toArray();

        double last = closes[closes.length - 1];
        long ts = candles.get(candles.size() - 1).getTime();

        log.debug("📊 Последняя свеча {}: ts={}, close={}", symbol, ts, last);

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
            executeTrade(OrderSide.BUY, cfg, last);
            lastEvent = "BUY";
        } else if (sellSignal) {
            executeTrade(OrderSide.SELL, cfg, last);
            lastEvent = "SELL";
        } else {
            lastEvent = "HOLD";
        }
    }

    // =====================================================================
    // TRADE EXECUTION
    // =====================================================================
    private void executeTrade(OrderSide side, SmartFusionStrategySettings cfg, double lastPrice) {
        try {
            double capital = cfg.getCapitalUsd();
            double qty = capital / lastPrice;

            orderExecutor.placeMarketOrder(
                    chatId,
                    exchange,
                    cfg.getNetworkType(),
                    symbol,
                    side,
                    BigDecimal.valueOf(qty)
            );

            double profitUsd = (side == OrderSide.BUY ? -1 : 1) * (lastPrice * qty * 0.001);
            pnlTracker.recordTrade(chatId, symbol, lastPrice, qty, profitUsd > 0, profitUsd);

            lastEvent = side.name();

        } catch (Exception e) {
            log.error("❌ Ошибка trade {}: {}", side, e.getMessage(), e);
        }
    }

    // =====================================================================
    // INDICATORS
    // =====================================================================

    private double ema(double[] data, int period) {
        double k = 2.0 / (period + 1);
        double ema = data[0];
        for (int i = 1; i < data.length; i++) {
            ema = data[i] * k + ema * (1 - k);
        }
        return ema;
    }

    private double rsi(double[] data, int period) {
        if (data.length < period + 1) return 50.0;

        double gain = 0, loss = 0;
        for (int i = data.length - period; i < data.length; i++) {
            double d = data[i] - data[i - 1];
            if (d > 0) gain += d; else loss -= d;
        }

        if (loss == 0) return 100;
        double rs = gain / loss;
        return 100 - (100 / (1 + rs));
    }

    private double[] bollinger(double[] data, int period, double k) {
        if (data.length < period) return new double[]{0, 0, 0};

        double mean = Arrays.stream(data, data.length - period, data.length)
                .average().orElse(0);

        double variance = Arrays.stream(data, data.length - period, data.length)
                                  .map(v -> Math.pow(v - mean, 2))
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
        return threadName;
    }

    @Override
    public SmartFusionCandleService getCandleService() {
        return candleService;
    }
}
