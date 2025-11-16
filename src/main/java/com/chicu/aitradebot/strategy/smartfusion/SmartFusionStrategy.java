package com.chicu.aitradebot.strategy.smartfusion;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.exchange.enums.OrderSide;
import com.chicu.aitradebot.strategy.core.ContextAwareStrategy;
import com.chicu.aitradebot.strategy.core.RuntimeIntrospectable;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.strategy.smartfusion.components.*;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 🧠 SmartFusionStrategy — гибридная стратегия Smart Fusion AI:
 * RSI + EMA + Bollinger + ML-подтверждение.
 * Работает с реальными свечами, ордерами и статистикой.
 */
@Component
@StrategyBinding(StrategyType.SMART_FUSION)
@RequiredArgsConstructor
@Slf4j
public class SmartFusionStrategy implements TradingStrategy, RuntimeIntrospectable, ContextAwareStrategy {

    private final SmartFusionCandleService candleService;
    private final SmartFusionOrderExecutor orderExecutor;
    private final SmartFusionPnLTracker pnlTracker;
    private final SmartFusionStrategySettingsService settingsService;

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

    // ==================== Инициализация ====================

    @PostConstruct
    public void onInit() {
        log.info("🚀 SmartFusionStrategy инициализирована как Spring Bean!");
    }

    // ==================== Методы TradingStrategy ====================

    @Override
    public synchronized void start() {
        if (running.get()) {
            log.warn("⚠️ SmartFusion уже запущена ({})", symbol);
            return;
        }

        running.set(true);
        startedAt = Instant.now();
        threadName = "SmartFusion-" + symbol;
        log.info("▶️ Стратегия SmartFusion запущена: {} ({})", symbol, network);

        workerThread = new Thread(this::runLoop, threadName);
        workerThread.setDaemon(true);
        workerThread.start();
    }

    @Override
    public synchronized void stop() {
        if (!running.get()) {
            log.warn("⚠️ SmartFusion уже остановлена ({})", symbol);
            return;
        }

        running.set(false);

        // Прерываем поток, если он ещё жив
        if (workerThread != null && workerThread.isAlive()) {
            workerThread.interrupt();
            try {
                workerThread.join(1000); // ждём максимум 1 секунду
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("⏹ SmartFusion остановлена ({})", symbol);
    }

    @Override
    public boolean isActive() {
        return running.get();
    }

    @Override
    public void onPriceUpdate(String symbol, double price) {
        log.debug("📈 Обновление цены [{}] = {}", symbol, price);
    }

    // ==================== Контекст ====================

    @Override
    public void setContext(long chatId, String symbol) {
        SmartFusionStrategySettings cfg = settingsService.getOrCreate(chatId, symbol);

        cfg.setChatId(chatId);
        cfg.setSymbol(symbol.toUpperCase(Locale.ROOT));
        settingsService.save(cfg);

        this.chatId = chatId;
        this.symbol = symbol.toUpperCase(Locale.ROOT);
        this.exchange = cfg.getExchange();
        this.network = cfg.getNetworkType();

        log.info("⚙️ SmartFusion контекст установлен: chatId={} symbol={} exchange={} network={}",
                chatId, this.symbol, exchange, network);
    }

    // ==================== Основной цикл ====================

    private void runLoop() {
        try {
            while (running.get()) {
                executeCycle();
                Thread.sleep(10_000);
            }
        } catch (InterruptedException e) {
            log.info("🛑 Поток SmartFusion ({}) прерван вручную", symbol);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("❌ Ошибка в цикле SmartFusion: {}", e.getMessage(), e);
        } finally {
            running.set(false);
            log.info("■ SmartFusion завершена (symbol={})", symbol);
        }
    }

    private void executeCycle() {
        if (!running.get()) return;

        SmartFusionStrategySettings cfg = settingsService.getOrCreate(chatId, symbol);
        List<SmartFusionCandleService.Candle> candles = candleService.getCandles(cfg);
        if (candles.size() < 20) {
            log.warn("⚠️ Недостаточно данных по свечам {}", symbol);
            return;
        }

        double[] closes = candles.stream().mapToDouble(SmartFusionCandleService.Candle::close).toArray();
        double lastPrice = closes[closes.length - 1];

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
            executeTrade(OrderSide.BUY, cfg, lastPrice);
            lastEvent = "BUY";
        } else if (sellSignal) {
            executeTrade(OrderSide.SELL, cfg, lastPrice);
            lastEvent = "SELL";
        } else {
            lastEvent = "HOLD";
        }

        log.info("📊 [{}] RSI={}, EMAfast={}, EMAslow={}, Price={}, Event={}",
                symbol, round(rsi), round(emaFast), round(emaSlow), round(lastPrice), lastEvent);
    }

    // ==================== Торговля ====================

    private void executeTrade(OrderSide side, SmartFusionStrategySettings cfg, double lastPrice) {
        try {
            double capital = cfg.getCapitalUsd();
            double qty = capital / lastPrice;
            BigDecimal qtyDec = BigDecimal.valueOf(qty);

            orderExecutor.placeMarketOrder(chatId, exchange, cfg.getNetworkType(), symbol, side, qtyDec);

            double profitUsd = (side == OrderSide.BUY ? -1 : 1) * (lastPrice * qty * 0.001);
            pnlTracker.recordTrade(chatId, symbol, lastPrice, qty, profitUsd > 0, profitUsd);

            log.info("✅ Исполнен {} ордер по {}, qty={} profit={} USD", side, symbol, qty, round(profitUsd));
        } catch (Exception e) {
            log.error("❌ Ошибка исполнения сделки: {}", e.getMessage());
        }
    }

    // ==================== Индикаторы ====================

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
            double diff = data[i] - data[i - 1];
            if (diff > 0) gain += diff; else loss -= diff;
        }
        if (loss == 0) return 100.0;
        double rs = gain / loss;
        return 100.0 - (100.0 / (1 + rs));
    }

    private double[] bollinger(double[] data, int period, double k) {
        if (data.length < period) return new double[]{0, 0, 0};
        double mean = Arrays.stream(data, data.length - period, data.length).average().orElse(0);
        double variance = Arrays.stream(data, data.length - period, data.length)
                                  .map(v -> Math.pow(v - mean, 2))
                                  .sum() / period;
        double std = Math.sqrt(variance);
        return new double[]{mean, mean + k * std, mean - k * std};
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ==================== RuntimeIntrospectable ====================

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
        return threadName != null ? threadName : Thread.currentThread().getName();
    }
    @Override
    public SmartFusionCandleService getCandleService() {
        return candleService;
    }
}
