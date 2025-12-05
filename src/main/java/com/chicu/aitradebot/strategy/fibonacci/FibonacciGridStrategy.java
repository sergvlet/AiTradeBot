package com.chicu.aitradebot.strategy.fibonacci;

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
 * Fibonacci Grid Strategy (v4, готова для StrategyEngine)
 *
 * Особенности:
 *  ✔ поддерживает setContext(chatId, symbol)
 *  ✔ загружает параметры из таблицы fibonacci_grid_strategy_settings
 *  ✔ имеет этап train() перед start()
 *  ✔ работает через StrategyEngine (tick)
 *  ✔ использует CandleProvider
 */
@Slf4j
@Component
@RequiredArgsConstructor
@StrategyBinding(StrategyType.FIBONACCI_GRID)
public class FibonacciGridStrategy implements TradingStrategy, ContextAwareStrategy {

    private final CandleProvider candleProvider;
    private final FibonacciGridStrategySettingsService settingsService;

    private long chatId;
    private String symbol;

    private final AtomicBoolean active = new AtomicBoolean(false);

    // Загружаемые параметры
    private int gridLevels;
    private double distancePct;
    private double takeProfitPct;
    private double stopLossPct;
    private int cachedCandlesLimit;
    private String timeframe;

    // =====================================================================
    // ✔ КОНТЕКСТ
    // =====================================================================

    @Override
    public void setContext(long chatId, String symbol) {
        this.chatId = chatId;
        this.symbol = symbol.toUpperCase();

        loadSettings();

        log.info("⚙️ FIBO context set: chatId={}, symbol={}, levels={}, distPct={}%",
                chatId, symbol, gridLevels, distancePct);
    }

    // =====================================================================
    // ✔ ОБУЧЕНИЕ (train)
    // =====================================================================

    /**
     * Обучение стратегии (перед запуском).
     * Сейчас базовая заглушка – можно подключить ML/ATR-кластеризацию.
     */
    private void train() {
        log.info("📚 FIBO TRAINING started (chatId={}, symbol={})", chatId, symbol);

        // Пример: анализ последних свечей
        List<CandleProvider.Candle> candles =
                candleProvider.getRecentCandles(chatId, symbol, timeframe, 300);

        if (candles.size() < 50) {
            log.warn("⚠️ FIBO TRAINING skipped – мало данных");
            return;
        }

        // Можно вычислять среднюю волатильность → подстраивать distancePct
        log.info("📘 FIBO TRAINING completed.");
    }

    // =====================================================================
    // ✔ START / STOP
    // =====================================================================

    @Override
    public synchronized void start() {
        loadSettings();   // всегда загружаем актуальные параметры
        train();          // обязательно обучаемся

        active.set(true);
        log.info("▶️ FIBO STARTED (chatId={}, symbol={})", chatId, symbol);
    }

    @Override
    public synchronized void stop() {
        active.set(false);
        log.info("⏹ FIBO STOPPED (chatId={}, symbol={})", chatId, symbol);
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    // =====================================================================
    // ✔ Основной цикл стратегии
    // =====================================================================

    @Override
    public void onPriceUpdate(String ignoredSymbol, BigDecimal price) {
        if (!active.get()) return;
        executeCycle();
    }

    private void executeCycle() {

        List<CandleProvider.Candle> candles =
                candleProvider.getRecentCandles(chatId, symbol, timeframe, cachedCandlesLimit);

        if (candles == null || candles.size() < 50) {
            return;
        }

        CandleProvider.Candle lastCandle = candles.get(candles.size() - 1);
        double lastPrice = lastCandle.close();

        double step = lastPrice * distancePct / 100.0;

        // Генерируем уровни
        for (int i = 1; i <= gridLevels; i++) {

            double buyLvl = lastPrice - i * step;
            double sellLvl = lastPrice + i * step;

            log.debug("📐 FIBO GRID {} → BUY={} SELL={} (step={}, last={})",
                    i, buyLvl, sellLvl, step, lastPrice);

            // ❗ пока не ставим реальные ордера
            // здесь можно дергать OrderService позже
        }
    }

    // =====================================================================
    // ✔ загрузка параметров из БД
    // =====================================================================

    private void loadSettings() {
        FibonacciGridStrategySettings set =
                settingsService.getOrCreate(chatId);

        this.gridLevels = set.getGridLevels();
        this.distancePct = set.getDistancePct();
        this.takeProfitPct = set.getTakeProfitPct();
        this.stopLossPct = set.getStopLossPct();
        this.cachedCandlesLimit = set.getCandleLimit();   // ← ИСПРАВЛЕНО
        this.timeframe = set.getTimeframe();

        log.info("🔧 FIBO settings loaded: levels={}, dist={}, TP={}, SL={}, tf={}, cache={}",
                gridLevels, distancePct, takeProfitPct, stopLossPct, timeframe, cachedCandlesLimit);
    }

}
