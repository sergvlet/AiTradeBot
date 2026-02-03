package com.chicu.aitradebot.strategy.executor;

import com.chicu.aitradebot.ai.ml.HttpMlSignalService;
import com.chicu.aitradebot.ai.ml.MlFeatures;
import com.chicu.aitradebot.ai.ml.MlPrediction;
import com.chicu.aitradebot.ai.ml.ModelKeyFactory;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.core.context.StrategyContext;
import com.chicu.aitradebot.strategy.core.runtime.StrategyRuntimeState;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategySignalExecutorImpl implements StrategySignalExecutor {

    private final StrategyLivePublisher live;

    // делаем зависимости опциональными (чтобы приложение не падало, если ML модуль выключен)
    private final ObjectProvider<HttpMlSignalService> ml;
    private final ObjectProvider<ModelKeyFactory> modelKeyFactory;

    @Override
    public void execute(Signal signal, StrategyContext ctx) {
        if (signal == null || ctx == null) return;

        StrategyRuntimeState state = ctx.getState();
        if (state == null) return;

        switch (signal.getType()) {
            case BUY -> handleBuy(signal, ctx, state);
            case SELL -> handleSell(signal, ctx, state);
            case EXIT -> handleExit(signal, ctx, state);
            case HOLD -> {
                // ничего
            }
        }
    }

    // =====================================================
    // BUY
    // =====================================================
    private void handleBuy(Signal signal,
                           StrategyContext ctx,
                           StrategyRuntimeState state) {

        if (state.hasOpenPosition()) {
            log.debug("⛔ BUY skipped — position already open");
            return;
        }

        if (!state.canEnterTrade()) {
            log.debug("⛔ BUY skipped — canEnterTrade=false (cooldown/risk limits)");
            return;
        }

        BigDecimal price = safePrice(ctx.getPrice());
        if (price == null) {
            log.debug("⛔ BUY skipped — price is null/invalid");
            return;
        }

        // ML Gate (если включён)
        if (!passesMlGate(signal, ctx)) {
            log.info("🟡 BUY blocked by ML gate | {}", safeReason(signal));
            return;
        }

        state.setEntryPrice(price);
        state.openPosition();

        // ===== TRADE MARKER =====
        live.pushTrade(
                ctx.getChatId(),
                ctx.getStrategyType(),
                ctx.getSymbol(),
                "BUY",
                price,
                BigDecimal.ONE,
                Instant.now()
        );

        // ===== PRICE LINES =====
        live.pushPriceLine(
                ctx.getChatId(),
                ctx.getStrategyType(),
                ctx.getSymbol(),
                "ENTRY",
                price
        );

        if (state.getTakeProfit() != null) {
            live.pushPriceLine(
                    ctx.getChatId(),
                    ctx.getStrategyType(),
                    ctx.getSymbol(),
                    "TP",
                    state.getTakeProfit()
            );
        }

        if (state.getStopLoss() != null) {
            live.pushPriceLine(
                    ctx.getChatId(),
                    ctx.getStrategyType(),
                    ctx.getSymbol(),
                    "SL",
                    state.getStopLoss()
            );
        }

        if (state.getWindowHigh() != null && state.getWindowLow() != null) {
            live.pushWindowZone(
                    ctx.getChatId(),
                    ctx.getStrategyType(),
                    ctx.getSymbol(),
                    state.getWindowHigh(),
                    state.getWindowLow()
            );
        } else {
            live.clearWindowZone(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());
        }

        log.info("🟢 BUY executed @ {} | {}", price, safeReason(signal));
    }

    // =====================================================
    // SELL
    // =====================================================
    private void handleSell(Signal signal,
                            StrategyContext ctx,
                            StrategyRuntimeState state) {

        if (state.hasOpenPosition()) {
            log.debug("⛔ SELL skipped — position already open");
            return;
        }

        if (!state.canEnterTrade()) {
            log.debug("⛔ SELL skipped — canEnterTrade=false (cooldown/risk limits)");
            return;
        }

        BigDecimal price = safePrice(ctx.getPrice());
        if (price == null) {
            log.debug("⛔ SELL skipped — price is null/invalid");
            return;
        }

        // ML Gate (если включён)
        if (!passesMlGate(signal, ctx)) {
            log.info("🟡 SELL blocked by ML gate | {}", safeReason(signal));
            return;
        }

        state.setEntryPrice(price);
        state.openPosition();

        live.pushTrade(
                ctx.getChatId(),
                ctx.getStrategyType(),
                ctx.getSymbol(),
                "SELL",
                price,
                BigDecimal.ONE,
                Instant.now()
        );

        live.pushPriceLine(
                ctx.getChatId(),
                ctx.getStrategyType(),
                ctx.getSymbol(),
                "ENTRY",
                price
        );

        if (state.getTakeProfit() != null) {
            live.pushPriceLine(
                    ctx.getChatId(),
                    ctx.getStrategyType(),
                    ctx.getSymbol(),
                    "TP",
                    state.getTakeProfit()
            );
        }

        if (state.getStopLoss() != null) {
            live.pushPriceLine(
                    ctx.getChatId(),
                    ctx.getStrategyType(),
                    ctx.getSymbol(),
                    "SL",
                    state.getStopLoss()
            );
        }

        if (state.getWindowHigh() != null && state.getWindowLow() != null) {
            live.pushWindowZone(
                    ctx.getChatId(),
                    ctx.getStrategyType(),
                    ctx.getSymbol(),
                    state.getWindowHigh(),
                    state.getWindowLow()
            );
        } else {
            live.clearWindowZone(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());
        }

        log.info("🔴 SELL executed @ {} | {}", price, safeReason(signal));
    }

    // =====================================================
    // EXIT
    // =====================================================
    private void handleExit(Signal signal,
                            StrategyContext ctx,
                            StrategyRuntimeState state) {

        if (!state.hasOpenPosition()) {
            return;
        }

        BigDecimal price = safePrice(ctx.getPrice());
        if (price == null) {
            state.closePosition();
            clearUi(ctx);
            log.info("🚪 EXIT position (no price) | {}", safeReason(signal));
            return;
        }

        state.closePosition();

        live.pushTrade(
                ctx.getChatId(),
                ctx.getStrategyType(),
                ctx.getSymbol(),
                "EXIT",
                price,
                BigDecimal.ONE,
                Instant.now()
        );

        clearUi(ctx);

        log.info("🚪 EXIT position | {}", safeReason(signal));
    }

    // =====================================================
    // ML GATE
    // =====================================================
    private boolean passesMlGate(Signal signal, StrategyContext ctx) {
        Object s = ctx.getSettings();
        if (!(s instanceof StrategySettings ss)) {
            // если в контексте не StrategySettings — гейт тут применить негде
            return true;
        }

        if (!ss.isMlGateEnabled()) return true;

        // если MANUAL — гейт не мешает ручному режиму
        if (ss.getAdvancedControlMode() == AdvancedControlMode.MANUAL) return true;

        HttpMlSignalService svc = ml.getIfAvailable();
        if (svc == null || !svc.isAvailable()) {
            // если гейт включён — но ML недоступен => безопаснее НЕ входить
            log.warn("⛔ ML Gate enabled, but ML service unavailable. Blocking entry.");
            return false;
        }

        String modelKey = resolveModelKey(ss, ctx);

        MlFeatures features = buildFeatures(ctx);
        MlPrediction pred = svc.predict(
                ctx.getChatId(),
                ctx.getSymbol(),
                ss.getTimeframe(), // у тебя timeframe хранится в StrategySettings
                modelKey,
                features
        );

        if (pred == null) {
            log.warn("⛔ ML prediction is null. Blocking entry.");
            return false;
        }

        BigDecimal minProb = ss.getGateMinProb();
        if (minProb == null) {
            // если порог не задан — гейт включён, но фильтра нет => пропускаем
            return true;
        }

        double p = switch (signal.getType()) {
            case BUY -> pred.probBuy();
            case SELL -> pred.probSell();
            default -> 1.0d;
        };

        boolean ok = BigDecimal.valueOf(p).compareTo(minProb) >= 0;

        if (!ok) {
            log.info("🟡 ML Gate reject: prob={} < minProb={} | modelVersion={}",
                    p, minProb, pred.modelVersion());
        } else {
            log.debug("✅ ML Gate pass: prob={} >= minProb={} | modelVersion={}",
                    p, minProb, pred.modelVersion());
        }

        return ok;
    }

    private String resolveModelKey(StrategySettings ss, StrategyContext ctx) {
        if (ss.getMlModelKey() != null && !ss.getMlModelKey().isBlank()) {
            return ss.getMlModelKey().trim();
        }

        ModelKeyFactory f = modelKeyFactory.getIfAvailable();
        if (f == null) {
            // если factory нет — вернём хоть какой-то стабильный ключ (минимум, чтобы ML мог матчиться по ключу)
            return ctx.getStrategyType() + ":" + ctx.getSymbol() + ":" + ss.getTimeframe();
        }

        String schemaHash = ss.getMlSchemaHash() == null ? "" : ss.getMlSchemaHash();
        return f.build(
                ctx.getStrategyType(),
                ctx.getSymbol(),
                ss.getTimeframe(),
                ctx.getExchange(),
                ctx.getNetworkType(),
                schemaHash
        );
    }

    private MlFeatures buildFeatures(StrategyContext ctx) {
        double[] closes = ctx.getCloses();
        BigDecimal priceBd = ctx.getPrice();
        double last = (priceBd != null ? priceBd.doubleValue() : lastClose(closes));

        Double momentum1 = null;
        Double volatilityPct = null;
        Double smaFastRel = null;
        Double smaSlowRel = null;

        if (closes != null && closes.length >= 2) {
            double prev = closes[closes.length - 2];
            if (prev > 0 && last > 0) {
                momentum1 = (last / prev) - 1.0d;
            }

            volatilityPct = stddevReturnsPct(closes);
            smaFastRel = smaRel(closes, last, Math.max(2, closes.length / 10));
            smaSlowRel = smaRel(closes, last, Math.max(3, closes.length / 3));
        }

        // volumeRel сейчас не из чего взять в StrategyContext — оставляем null
        return new MlFeatures(momentum1, volatilityPct, null, smaFastRel, smaSlowRel);
    }

    private double lastClose(double[] closes) {
        if (closes == null || closes.length == 0) return 0.0d;
        return closes[closes.length - 1];
    }

    private Double smaRel(double[] closes, double last, int period) {
        if (closes == null || closes.length == 0) return null;
        int p = Math.min(period, closes.length);
        if (p <= 0) return null;

        double sum = 0.0d;
        for (int i = closes.length - p; i < closes.length; i++) {
            sum += closes[i];
        }
        double sma = sum / p;
        if (sma <= 0 || last <= 0) return null;
        return (last / sma) - 1.0d;
    }

    private Double stddevReturnsPct(double[] closes) {
        if (closes == null || closes.length < 3) return null;

        int n = closes.length - 1;
        double[] r = new double[n];

        int k = 0;
        for (int i = 1; i < closes.length; i++) {
            double a = closes[i - 1];
            double b = closes[i];
            if (a <= 0 || b <= 0) continue;
            r[k++] = (b / a) - 1.0d;
        }

        if (k < 2) return null;

        double mean = 0.0d;
        for (int i = 0; i < k; i++) mean += r[i];
        mean /= k;

        double var = 0.0d;
        for (int i = 0; i < k; i++) {
            double d = r[i] - mean;
            var += d * d;
        }
        var /= (k - 1);

        double sd = Math.sqrt(var);
        return sd * 100.0d;
    }

    // =====================================================
    // HELPERS
    // =====================================================
    private void clearUi(StrategyContext ctx) {
        live.clearPriceLines(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());
        live.clearTpSl(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());
        live.clearWindowZone(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());
    }

    private BigDecimal safePrice(BigDecimal price) {
        if (price == null) return null;
        if (price.signum() <= 0) return null;
        return price;
    }

    private String safeReason(Signal signal) {
        try {
            return signal.getReason() != null ? signal.getReason() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
