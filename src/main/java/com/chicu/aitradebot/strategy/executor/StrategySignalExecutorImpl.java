package com.chicu.aitradebot.strategy.executor;

import com.chicu.aitradebot.ai.ml.ModelKeyFactory;
import com.chicu.aitradebot.ai.ml.MlClient;
import com.chicu.aitradebot.ai.ml.dto.MlHealthResponse;
import com.chicu.aitradebot.ai.ml.dto.MlPredictRequest;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategySignalExecutorImpl implements StrategySignalExecutor {

    private final StrategyLivePublisher live;

    private final ObjectProvider<MlClient> mlClientProvider;
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
            case HOLD -> { /* no-op */ }
        }
    }

    private void handleBuy(Signal signal, StrategyContext ctx, StrategyRuntimeState state) {
        if (state.hasOpenPosition()) return;
        if (!state.canEnterTrade()) return;

        BigDecimal price = safePrice(ctx.getPrice());
        if (price == null) return;

        if (!passesMlGate(signal, ctx)) {
            log.info("🟡 BUY blocked by ML gate | {}", safeReason(signal));
            return;
        }

        state.setEntryPrice(price);
        state.openPosition();

        live.pushTrade(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol(), "BUY", price, BigDecimal.ONE, Instant.now());
        live.pushPriceLine(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol(), "ENTRY", price);

        if (state.getTakeProfit() != null) live.pushPriceLine(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol(), "TP", state.getTakeProfit());
        if (state.getStopLoss() != null) live.pushPriceLine(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol(), "SL", state.getStopLoss());

        if (state.getWindowHigh() != null && state.getWindowLow() != null) {
            live.pushWindowZone(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol(), state.getWindowHigh(), state.getWindowLow());
        } else {
            live.clearWindowZone(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());
        }
    }

    private void handleSell(Signal signal, StrategyContext ctx, StrategyRuntimeState state) {
        if (state.hasOpenPosition()) return;
        if (!state.canEnterTrade()) return;

        BigDecimal price = safePrice(ctx.getPrice());
        if (price == null) return;

        if (!passesMlGate(signal, ctx)) {
            log.info("🟡 SELL blocked by ML gate | {}", safeReason(signal));
            return;
        }

        state.setEntryPrice(price);
        state.openPosition();

        live.pushTrade(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol(), "SELL", price, BigDecimal.ONE, Instant.now());
        live.pushPriceLine(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol(), "ENTRY", price);

        if (state.getTakeProfit() != null) live.pushPriceLine(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol(), "TP", state.getTakeProfit());
        if (state.getStopLoss() != null) live.pushPriceLine(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol(), "SL", state.getStopLoss());

        if (state.getWindowHigh() != null && state.getWindowLow() != null) {
            live.pushWindowZone(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol(), state.getWindowHigh(), state.getWindowLow());
        } else {
            live.clearWindowZone(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());
        }
    }

    private void handleExit(Signal signal, StrategyContext ctx, StrategyRuntimeState state) {
        if (!state.hasOpenPosition()) return;

        BigDecimal price = safePrice(ctx.getPrice());
        state.closePosition();

        if (price != null) {
            live.pushTrade(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol(), "EXIT", price, BigDecimal.ONE, Instant.now());
        }

        clearUi(ctx);
    }

    private boolean passesMlGate(Signal signal, StrategyContext ctx) {
        Object raw = ctx.getSettings();
        if (!(raw instanceof StrategySettings ss)) return true;

        if (!ss.isMlGateEnabled()) return true;

        AdvancedControlMode mode = ss.getAdvancedControlMode() != null ? ss.getAdvancedControlMode() : AdvancedControlMode.MANUAL;
        if (mode == AdvancedControlMode.MANUAL) return true;

        BigDecimal minProb = ss.getGateMinProb();
        if (minProb == null || minProb.signum() <= 0) return true;

        MlClient ml = mlClientProvider.getIfAvailable();
        if (ml == null) return false;

        if (!isMlHealthyAndReady(ml)) return false;

        String modelKey = resolveModelKey(ss, ctx);

        MlPredictRequest req = new MlPredictRequest();
        req.setChatId(ctx.getChatId());
        req.setStrategyType(String.valueOf(ctx.getStrategyType() != null ? ctx.getStrategyType() : ss.getType()));
        req.setSymbol(safeUpper(ctx.getSymbol()));
        req.setTimeframe(ss.getTimeframe());
        req.setModelKey(modelKey);
        req.setFeatures(buildFeatures(ctx));
        req.setTsMs(Instant.now().toEpochMilli());
        req.setSchemaHash(ss.getMlSchemaHash());

        MlPredictResponse resp;
        try {
            resp = ml.predict(req);
        } catch (Exception e) {
            return false;
        }

        if (resp == null || !resp.isOk() || resp.getProba() == null) return false;

        double proba = resp.getProba();
        if (!Double.isFinite(proba)) proba = 0.0;

        double pBuy = clamp01(proba);
        double pSell = clamp01(1.0 - proba);

        double threshold = minProb.doubleValue();

        return switch (signal.getType()) {
            case BUY -> (pBuy + 1e-12) >= threshold;
            case SELL -> (pSell + 1e-12) >= threshold;
            default -> true;
        };
    }

    private boolean isMlHealthyAndReady(MlClient ml) {
        try {
            MlHealthResponse h = ml.health();
            boolean ok = mlOk(h);
            boolean modelOk = mlModelExistsOrUnknown(h); // если поля нет — не валим
            return ok && modelOk;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ✅ Совместимая проверка ok:
     * - isOk()
     * - getOk()
     * - поле ok
     */
    private static boolean mlOk(MlHealthResponse h) {
        if (h == null) return false;

        try {
            Method m = h.getClass().getMethod("isOk");
            Object v = m.invoke(h);
            if (v instanceof Boolean b) return b;
        } catch (Exception ignored) {}

        try {
            Method m = h.getClass().getMethod("getOk");
            Object v = m.invoke(h);
            if (v instanceof Boolean b) return b;
        } catch (Exception ignored) {}

        try {
            Field f = h.getClass().getDeclaredField("ok");
            f.setAccessible(true);
            Object v = f.get(h);
            if (v instanceof Boolean b) return b;
        } catch (Exception ignored) {}

        return false;
    }

    /**
     * ✅ Совместимая проверка model_exists:
     * - getModel_exists()/isModel_exists()
     * - getModelExists()/isModelExists()
     * - поле model_exists/modelExists
     *
     * Если этого поля нет в DTO — считаем "unknown" и НЕ блокируем.
     */
    private static boolean mlModelExistsOrUnknown(MlHealthResponse h) {
        if (h == null) return true;

        Boolean v = readBool(h, "getModel_exists");
        if (v != null) return v;

        v = readBool(h, "isModel_exists");
        if (v != null) return v;

        v = readBool(h, "getModelExists");
        if (v != null) return v;

        v = readBool(h, "isModelExists");
        if (v != null) return v;

        v = readBoolField(h, "model_exists");
        if (v != null) return v;

        v = readBoolField(h, "modelExists");
        if (v != null) return v;

        return true;
    }

    private static Boolean readBool(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            Object r = m.invoke(target);
            return (r instanceof Boolean b) ? b : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Boolean readBoolField(Object target, String field) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            Object r = f.get(target);
            return (r instanceof Boolean b) ? b : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveModelKey(StrategySettings ss, StrategyContext ctx) {
        if (ss.getMlModelKey() != null && !ss.getMlModelKey().isBlank()) {
            return ss.getMlModelKey().trim();
        }

        ModelKeyFactory f = modelKeyFactory.getIfAvailable();
        String type = String.valueOf(ctx.getStrategyType() != null ? ctx.getStrategyType() : ss.getType());
        String symbol = safeUpper(ctx.getSymbol());
        String tf = ss.getTimeframe();

        if (f == null) {
            return type + ":" + symbol + ":" + tf;
        }

        // ✅ ВАЖНО: build(String strategyType, String symbol, String timeframe)
        return f.build(type, symbol, tf);
    }

    private Map<String, Object> buildFeatures(StrategyContext ctx) {
        double[] closes = ctx.getCloses();
        BigDecimal priceBd = ctx.getPrice();
        double last = (priceBd != null ? priceBd.doubleValue() : lastClose(closes));

        Double momentum1 = null;
        Double volatilityPct = null;
        Double smaFastRel = null;
        Double smaSlowRel = null;

        if (closes != null && closes.length >= 2) {
            double prev = closes[closes.length - 2];
            if (prev > 0 && last > 0) momentum1 = (last / prev) - 1.0d;

            volatilityPct = stddevReturnsPct(closes);
            smaFastRel = smaRel(closes, last, Math.max(2, closes.length / 10));
            smaSlowRel = smaRel(closes, last, Math.max(3, closes.length / 3));
        }

        Map<String, Object> f = new HashMap<>();
        f.put("momentum1", momentum1);
        f.put("volatilityPct", volatilityPct);
        f.put("smaFastRel", smaFastRel);
        f.put("smaSlowRel", smaSlowRel);
        f.put("lastPrice", last);
        return f;
    }

    private static double lastClose(double[] closes) {
        if (closes == null || closes.length == 0) return 0.0d;
        return closes[closes.length - 1];
    }

    private static Double smaRel(double[] closes, double last, int period) {
        if (closes == null || closes.length == 0) return null;
        int p = Math.min(period, closes.length);
        if (p <= 0) return null;

        double sum = 0.0d;
        for (int i = closes.length - p; i < closes.length; i++) sum += closes[i];
        double sma = sum / p;
        if (sma <= 0 || last <= 0) return null;
        return (last / sma) - 1.0d;
    }

    private static Double stddevReturnsPct(double[] closes) {
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

        return Math.sqrt(var) * 100.0d;
    }

    private void clearUi(StrategyContext ctx) {
        live.clearPriceLines(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());
        live.clearTpSl(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());
        live.clearWindowZone(ctx.getChatId(), ctx.getStrategyType(), ctx.getSymbol());
    }

    private static BigDecimal safePrice(BigDecimal price) {
        if (price == null) return null;
        if (price.signum() <= 0) return null;
        return price;
    }

    private static String safeReason(Signal signal) {
        try {
            return signal.getReason() != null ? signal.getReason() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String safeUpper(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT);
    }

    private static double clamp01(double v) {
        if (!Double.isFinite(v)) return 0.0;
        if (v < 0) return 0.0;
        if (v > 1) return 1.0;
        return v;
    }
}