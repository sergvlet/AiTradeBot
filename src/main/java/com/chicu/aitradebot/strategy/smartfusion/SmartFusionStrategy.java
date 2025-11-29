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

    @Getter
    private String lastEvent = "INIT";
    private Instant startedAt;

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

        running.set(true);
        startedAt = Instant.now();

        log.info("▶️ SmartFusion запущена: chatId={}, symbol={}, exchange={}, network={}",
                chatId, symbol, exchange, network);
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

        running.set(false);

        log.info("⏹ SmartFusion остановлена: chatId={}, symbol={}", chatId, symbol);
    }

    @Override
    public boolean isActive() {
        return running.get();
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

        log.info("⚙️ Контекст SmartFusion установлен: chatId={}, symbol={}, exchange={}, network={}",
                chatId, symbol, exchange, network);
    }

    // =====================================================================
    // EVENT-DRIVEN
    // =====================================================================
    @Override
    public void onPriceUpdate(String symbol, double price) {
        if (!running.get()) return;

        try {
            executeCycle();
        } catch (Exception e) {
            log.error("❌ SmartFusion onPriceUpdate error: {}", e.getMessage(), e);
        }
    }

    // =====================================================================
    // ONE CYCLE — вызывается при каждом новом тике
    // =====================================================================
    private void executeCycle() {

        SmartFusionStrategySettings cfg = settingsService.getOrCreate(chatId);

        int limit = cfg.getCandleLimit() > 0 ? cfg.getCandleLimit() : 300;
        String timeframe = cfg.getTimeframe() != null ? cfg.getTimeframe() : "1m";

        List<CandleProvider.Candle> candles =
                candleProvider.getRecentCandles(chatId, symbol, timeframe, limit);

        if (candles.size() < 20) return;

        double[] closes = candles.stream()
                .mapToDouble(CandleProvider.Candle::close)
                .toArray();

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
            if (d > 0) gain += d;
            else loss -= d;
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
        return "SMARTFUSION-" + symbol;
    }
}
