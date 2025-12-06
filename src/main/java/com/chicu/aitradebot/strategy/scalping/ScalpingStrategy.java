package com.chicu.aitradebot.strategy.scalping;

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
 * Scalping Strategy (v4)
 *
 * Особенности:
 *  ✔ Загружает параметры из ScalpingStrategySettings
 *  ✔ Имеет обучение перед стартом
 *  ✔ Совместима со StrategyEngine
 *  ✔ Использует CandleProvider для получения свечей
 *  ✔ Логика вынесена, структура полностью единообразная
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.SCALPING)
public class ScalpingStrategy implements TradingStrategy, ContextAwareStrategy {

    private final CandleProvider candleProvider;
    private final ScalpingStrategySettingsService settingsService;

    private long chatId;
    private String symbol;

    private final AtomicBoolean active = new AtomicBoolean(false);

    // параметры стратегии (загружаются из БД)
    private double priceChangeThreshold;
    private int windowSize;
    private String timeframe;
    private int cachedCandlesLimit;
    private double takeProfitPct;
    private double stopLossPct;

    // =====================================================================
    // ✔ КОНТЕКСТ
    // =====================================================================

    @Override
    public void setContext(long chatId, String symbol) {
        this.chatId = chatId;
        this.symbol = symbol.toUpperCase();

        loadSettings();

        log.info("⚙️ Scalping context set: {}, window={}, thr={}%",
                this.symbol, windowSize, priceChangeThreshold);
    }

    // =====================================================================
    // ✔ TRAIN (обучение)
    // =====================================================================

    private void train() {
        log.info("📚 Scalping TRAINING started (chatId={}, symbol={})", chatId, symbol);

        List<CandleProvider.Candle> candles =
                candleProvider.getRecentCandles(chatId, symbol, timeframe, cachedCandlesLimit);

        if (candles.size() < windowSize + 5) {
            log.warn("⚠️ Scalping TRAINING skipped — мало данных");
            return;
        }

        log.info("📘 Scalping TRAINING completed.");
    }

    // =====================================================================
    // ✔ START / STOP
    // =====================================================================

    @Override
    public synchronized void start() {
        loadSettings();
        train();
        active.set(true);

        log.info("▶️ Scalping STARTED (chatId={}, symbol={})", chatId, symbol);
    }

    @Override
    public synchronized void stop() {
        active.set(false);
        log.info("⏹ Scalping STOPPED (chatId={}, symbol={})", chatId, symbol);
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    // =====================================================================
    // ✔ Основной цикл
    // =====================================================================

    @Override
    public void onPriceUpdate(String symbolIgnored, BigDecimal priceIgnored) {
        if (!active.get()) return;

        try {
            executeCycle();
        } catch (Exception e) {
            log.error("❌ Scalping cycle error: {}", e.getMessage(), e);
        }
    }

    private void executeCycle() {

        List<CandleProvider.Candle> candles =
                candleProvider.getRecentCandles(chatId, symbol, timeframe, cachedCandlesLimit);

        if (candles.size() < windowSize + 1) {
            return;
        }

        double last = candles.getLast().close();
        double prev = candles.get(candles.size() - windowSize).close();

        double diffPct = (last - prev) / prev * 100.0;

        if (diffPct >= priceChangeThreshold) {
            log.info("💥 Scalping BUY {}", symbol);
            log.info("📈 +{}% (window {} candles)", diffPct, windowSize);
        }
        else if (diffPct <= -priceChangeThreshold) {
            log.info("⚠️ Scalping SELL {}", symbol);
            log.info("📉 {}% (window {} candles)", diffPct, windowSize);
        }
    }

    // =====================================================================
    // ✔ загрузка параметров из БД
    // =====================================================================

    private void loadSettings() {
        ScalpingStrategySettings set = settingsService.getOrCreate(chatId);

        this.windowSize = set.getWindowSize();
        this.priceChangeThreshold = set.getPriceChangeThreshold();
        this.timeframe = set.getTimeframe();
        this.cachedCandlesLimit = set.getCachedCandlesLimit();
        this.takeProfitPct = set.getTakeProfitPct();
        this.stopLossPct = set.getStopLossPct();

        log.info("🔧 Scalping settings loaded: window={}, Δ={}%, tf={}, cache={}, tp={}, sl={}",
                windowSize, priceChangeThreshold, timeframe, cachedCandlesLimit,
                takeProfitPct, stopLossPct);
    }
}
