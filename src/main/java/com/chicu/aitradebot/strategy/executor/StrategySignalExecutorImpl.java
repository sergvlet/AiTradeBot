package com.chicu.aitradebot.strategy.executor;

import com.chicu.aitradebot.ai.ml.ModelKeyFactory;
import com.chicu.aitradebot.ai.ml.MlClient;
import com.chicu.aitradebot.ai.ml.dto.MlHealthResponse;
import com.chicu.aitradebot.ai.ml.dto.MlPredictRequest;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.core.context.StrategyContext;
import com.chicu.aitradebot.strategy.core.runtime.StrategyRuntimeState;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.trade.ExitResult;
import com.chicu.aitradebot.trade.PositionStore;
import com.chicu.aitradebot.trade.TradeExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategySignalExecutorImpl implements StrategySignalExecutor {

    private static final BigDecimal DEFAULT_DIFF_PCT = new BigDecimal("0.010000");
    private static final BigDecimal DEFAULT_TP_PCT = new BigDecimal("1.00");
    private static final BigDecimal DEFAULT_SL_PCT = new BigDecimal("0.80");

    private final StrategyLivePublisher live;
    private final TradeExecutionService tradeExecutionService;
    private final PositionStore positionStore;

    private final ObjectProvider<MlClient> mlClientProvider;
    private final ObjectProvider<ModelKeyFactory> modelKeyFactory;

    @Override
    public void execute(Signal signal, StrategyContext ctx) {
        if (signal == null || ctx == null) return;

        StrategyRuntimeState state = ctx.getState();
        if (state == null) return;

        syncStateFromPositionStore(ctx, state);

        switch (signal.getType()) {
            case BUY -> handleBuy(signal, ctx, state);
            case SELL -> handleSell(signal, ctx, state);
            case EXIT -> handleExit(signal, ctx, state);
            case HOLD -> { /* no-op */ }
        }
    }

    private void handleBuy(Signal signal, StrategyContext ctx, StrategyRuntimeState state) {
        StrategySettings ss = extractStrategySettings(ctx);
        if (ss == null) {
            log.warn("⚠️ StrategySignalExecutor BUY skipped: StrategySettings not found");
            return;
        }

        if (state.hasOpenPosition()) return;
        if (!state.canEnterTrade()) return;

        BigDecimal price = safePrice(ctx.getPrice());
        if (price == null) return;

        if (!passesMlGate(signal, ctx)) {
            log.info("🟡 BUY blocked by ML gate | {}", safeReason(signal));
            return;
        }

        BigDecimal tpPct = resolveStrategyPct(ctx, ss, "getTakeProfitPct", DEFAULT_TP_PCT);
        BigDecimal slPct = resolveStrategyPct(ctx, ss, "getStopLossPct", DEFAULT_SL_PCT);
        BigDecimal diffPct = resolveDiffPct(signal);

        try {
            var entry = tradeExecutionService.executeEntry(
                    ctx.getChatId(),
                    resolveStrategyType(ctx, ss),
                    safeUpper(ctx.getSymbol()),
                    price,
                    diffPct,
                    Instant.now(),
                    ss,
                    tpPct,
                    slPct
            );

            if (entry == null || !entry.executed()) {
                log.info("🟡 BUY rejected by trade-layer | reason={}", entry != null ? entry.reason() : "null");
                return;
            }

            BigDecimal entryPrice = positive(firstNonNull(entry.entryPrice(), price));
            BigDecimal qty = positive(entry.qty());
            BigDecimal tp = positive(entry.tp());
            BigDecimal sl = positive(entry.sl());

            state.setEntryPrice(entryPrice);
            state.setTakeProfit(tp);
            state.setStopLoss(sl);
            state.openPosition();

            live.pushTrade(ctx.getChatId(), resolveStrategyType(ctx, ss), ctx.getSymbol(), "BUY", entryPrice, qty, Instant.now());
            live.pushPriceLine(ctx.getChatId(), resolveStrategyType(ctx, ss), ctx.getSymbol(), "ENTRY", entryPrice);
            if (tp != null) live.pushPriceLine(ctx.getChatId(), resolveStrategyType(ctx, ss), ctx.getSymbol(), "TP", tp);
            if (sl != null) live.pushPriceLine(ctx.getChatId(), resolveStrategyType(ctx, ss), ctx.getSymbol(), "SL", sl);

            if (state.getWindowHigh() != null && state.getWindowLow() != null) {
                live.pushWindowZone(ctx.getChatId(), resolveStrategyType(ctx, ss), ctx.getSymbol(), state.getWindowHigh(), state.getWindowLow());
            } else {
                live.clearWindowZone(ctx.getChatId(), resolveStrategyType(ctx, ss), ctx.getSymbol());
            }
        } catch (Exception e) {
            log.error("❌ StrategySignalExecutor BUY failed chatId={} symbol={} err={}",
                    ctx.getChatId(), ctx.getSymbol(), e.toString(), e);
        }
    }

    private void handleSell(Signal signal, StrategyContext ctx, StrategyRuntimeState state) {
        syncStateFromPositionStore(ctx, state);

        if (state.hasOpenPosition()) {
            handleExit(signal, ctx, state);
            return;
        }

        log.info("🟡 SELL signal ignored: no open position, short entry is disabled for shared spot trade-layer | {}", safeReason(signal));
    }

    private void handleExit(Signal signal, StrategyContext ctx, StrategyRuntimeState state) {
        StrategySettings ss = extractStrategySettings(ctx);
        StrategyType type = resolveStrategyType(ctx, ss);

        syncStateFromPositionStore(ctx, state);
        if (!state.hasOpenPosition()) return;

        BigDecimal price = safePrice(ctx.getPrice());
        if (price == null) return;

        BigDecimal qty = positive(state.getEntryPrice()) != null ? positive(resolveOpenQty(ctx)) : null;
        if (qty == null) {
            qty = resolveQtyFromPositionStore(ctx);
        }
        if (qty == null || qty.signum() <= 0) {
            log.warn("⚠️ EXIT skipped: qty not resolved | chatId={} type={} symbol={}", ctx.getChatId(), type, ctx.getSymbol());
            return;
        }

        String exchange = safeUpper(ctx.getExchange());
        NetworkType network = ctx.getNetworkType();

        try {
            ExitResult exit = invokeExitNow(
                    ctx.getChatId(),
                    type,
                    safeUpper(ctx.getSymbol()),
                    price,
                    Instant.now(),
                    qty,
                    positive(state.getTakeProfit()) != null ? state.getTakeProfit() : price,
                    positive(state.getStopLoss()) != null ? state.getStopLoss() : BigDecimal.ZERO,
                    exchange,
                    network,
                    "LEGACY_SIGNAL_EXIT"
            );

            if (exit == null || !exit.executed()) {
                log.info("🟡 EXIT rejected by trade-layer | reason={}", exit != null ? exit.reason() : "null");
                return;
            }

            state.closePosition();
            if (price != null) {
                live.pushTrade(ctx.getChatId(), type, ctx.getSymbol(), "EXIT", price, qty, Instant.now());
            }
            clearUi(ctx, type);
        } catch (Exception e) {
            log.error("❌ StrategySignalExecutor EXIT failed chatId={} symbol={} err={}",
                    ctx.getChatId(), ctx.getSymbol(), e.toString(), e);
        }
    }

    private void syncStateFromPositionStore(StrategyContext ctx, StrategyRuntimeState state) {
        StrategySettings ss = extractStrategySettings(ctx);
        StrategyType type = resolveStrategyType(ctx, ss);
        String exchange = safeUpper(ctx.getExchange());
        NetworkType network = ctx.getNetworkType();
        String symbol = safeUpper(ctx.getSymbol());

        if (ctx.getChatId() == null || type == null || exchange == null || network == null || symbol == null) {
            return;
        }

        try {
            Optional<PositionStore.PositionSnapshot> opt = positionStore.getPosition(
                    ctx.getChatId(),
                    type,
                    exchange,
                    network,
                    symbol
            );

            if (opt.isPresent()) {
                PositionStore.PositionSnapshot snap = opt.get();
                state.setEntryPrice(snap.entryPrice());
                state.setTakeProfit(snap.tp());
                state.setStopLoss(snap.sl());
                if (!state.hasOpenPosition()) {
                    state.openPosition();
                } else {
                    state.touch();
                }
                return;
            }

            if (state.hasOpenPosition()) {
                state.closePosition();
            }
        } catch (Exception e) {
            log.debug("⚠️ StrategySignalExecutor state sync skipped chatId={} type={} symbol={} err={}",
                    ctx.getChatId(), type, symbol, e.toString());
        }
    }

    private BigDecimal resolveQtyFromPositionStore(StrategyContext ctx) {
        StrategySettings ss = extractStrategySettings(ctx);
        StrategyType type = resolveStrategyType(ctx, ss);
        String exchange = safeUpper(ctx.getExchange());
        NetworkType network = ctx.getNetworkType();
        String symbol = safeUpper(ctx.getSymbol());

        if (ctx.getChatId() == null || type == null || exchange == null || network == null || symbol == null) {
            return null;
        }

        try {
            return positionStore.getPosition(ctx.getChatId(), type, exchange, network, symbol)
                    .map(PositionStore.PositionSnapshot::qty)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal resolveOpenQty(StrategyContext ctx) {
        StrategyRuntimeState state = ctx.getState();
        if (state == null) return null;

        Object value = reflectAny(state, "getEntryQty");
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private ExitResult invokeExitNow(Long chatId,
                                     StrategyType type,
                                     String symbol,
                                     BigDecimal price,
                                     Instant time,
                                     BigDecimal entryQty,
                                     BigDecimal tp,
                                     BigDecimal sl,
                                     String exchange,
                                     NetworkType network,
                                     String exitReason) throws Exception {

        for (Method method : tradeExecutionService.getClass().getMethods()) {
            if (!"executeExitNow".equals(method.getName())) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 10 && types.length != 11) continue;

            Object[] args = new Object[types.length];
            int i = 0;
            args[i++] = chatId;
            args[i++] = type;
            args[i++] = symbol;
            args[i++] = price;
            args[i++] = time;
            args[i++] = entryQty;
            args[i++] = tp;
            args[i++] = sl;
            args[i++] = exchange;
            args[i++] = network;
            if (types.length == 11) {
                args[i] = exitReason;
            }

            Object result = method.invoke(tradeExecutionService, args);
            if (result instanceof ExitResult exitResult) {
                return exitResult;
            }
            return null;
        }

        return tradeExecutionService.executeExitIfHit(
                chatId,
                type,
                symbol,
                price,
                time,
                true,
                entryQty,
                tp != null ? tp : price,
                sl != null ? sl : BigDecimal.ZERO,
                exchange,
                network
        );
    }

    private StrategySettings extractStrategySettings(StrategyContext ctx) {
        if (ctx == null) return null;
        Object raw = ctx.getSettings();
        if (raw instanceof StrategySettings ss) return ss;

        try {
            return ctx.getTypedSettings(StrategySettings.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private StrategyType resolveStrategyType(StrategyContext ctx, StrategySettings ss) {
        if (ctx != null && ctx.getStrategyType() != null) {
            return ctx.getStrategyType();
        }
        return ss != null ? ss.getType() : null;
    }

    private BigDecimal resolveStrategyPct(StrategyContext ctx,
                                          StrategySettings ss,
                                          String methodName,
                                          BigDecimal fallback) {
        Object raw = ctx != null ? ctx.getSettings() : null;
        BigDecimal value = reflectBigDecimal(raw, methodName);
        if (positive(value) != null) return value;
        value = reflectBigDecimal(ss, methodName);
        if (positive(value) != null) return value;
        return fallback;
    }

    private BigDecimal resolveDiffPct(Signal signal) {
        BigDecimal confidence = reflectBigDecimal(signal, "getConfidence");
        if (confidence != null && confidence.signum() > 0) {
            return confidence.setScale(6, RoundingMode.HALF_UP);
        }
        return DEFAULT_DIFF_PCT;
    }

    private boolean passesMlGate(Signal signal, StrategyContext ctx) {
        StrategySettings ss = extractStrategySettings(ctx);
        if (ss == null) return true;

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
            boolean modelOk = mlModelExistsOrUnknown(h);
            return ok && modelOk;
        } catch (Exception e) {
            return false;
        }
    }

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
        f.put("exchange", safeUpper(ctx.getExchange()));
        f.put("network", ctx.getNetworkType() != null ? ctx.getNetworkType().name() : null);
        return f;
    }

    private void clearUi(StrategyContext ctx, StrategyType type) {
        live.clearPriceLines(ctx.getChatId(), type, ctx.getSymbol());
        live.clearTpSl(ctx.getChatId(), type, ctx.getSymbol());
        live.clearWindowZone(ctx.getChatId(), type, ctx.getSymbol());
    }

    private static Object reflectAny(Object obj, String method) {
        if (obj == null || isBlank(method)) return null;
        try {
            var m = obj.getClass().getMethod(method);
            if (m.getParameterCount() != 0) return null;
            return m.invoke(obj);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static BigDecimal reflectBigDecimal(Object obj, String method) {
        Object v = reflectAny(obj, method);
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private static BigDecimal firstNonNull(BigDecimal a, BigDecimal b) {
        return a != null ? a : b;
    }

    private static BigDecimal positive(BigDecimal v) {
        return v != null && v.signum() > 0 ? v : null;
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static double clamp01(double v) {
        if (!Double.isFinite(v)) return 0.0;
        if (v < 0) return 0.0;
        if (v > 1) return 1.0;
        return v;
    }
}
