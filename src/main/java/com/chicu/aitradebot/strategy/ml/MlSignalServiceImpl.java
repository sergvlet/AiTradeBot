package com.chicu.aitradebot.strategy.ml;

import com.chicu.aitradebot.ai.ml.MlClient;
import com.chicu.aitradebot.ai.ml.dto.MlHealthResponse;
import com.chicu.aitradebot.ai.ml.dto.MlPredictRequest;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
import com.chicu.aitradebot.ai.ml.dto.MlPrediction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlSignalServiceImpl implements MlSignalService {

    private static final long HEALTH_CACHE_MS = 2_000;

    private final ObjectProvider<MlClient> ml;

    // кешируем health, чтобы не долбить /health на каждый тик
    private volatile Instant lastHealthAt;
    private volatile boolean lastHealthOk;

    @Override
    public boolean isAvailable() {
        MlClient c = ml.getIfAvailable();
        if (c == null) return false;

        Instant now = Instant.now();
        Instant prev = lastHealthAt;
        if (prev != null && Duration.between(prev, now).toMillis() < HEALTH_CACHE_MS) {
            return lastHealthOk;
        }

        boolean ok = false; // ✅ чтобы компилятор не ругался (finally использует ok)
        try {
            MlHealthResponse h = c.health();
            ok = mlOk(h);
        } catch (Exception e) {
            ok = false;
        } finally {
            lastHealthOk = ok;
            lastHealthAt = now;
        }

        return ok;
    }

    @Override
    public MlPrediction predict(Long chatId,
                                String symbol,
                                String timeframe,
                                String modelKey,
                                String schemaHash,
                                Map<String, Object> features) {

        MlClient c = ml.getIfAvailable();
        if (c == null) return MlPrediction.fail("ml_client_missing");
        if (!isAvailable()) return MlPrediction.fail("ml_unhealthy");

        try {
            // Контракт твоего MlPredictRequest: только features-map
            Map<String, Object> map = new HashMap<>(features != null ? features : Map.of());

            // мета в request features (раз DTO такой)
            if (chatId != null) map.put("chatId", chatId);
            if (symbol != null) map.put("symbol", symbol);
            if (timeframe != null) map.put("timeframe", timeframe);
            if (modelKey != null) map.put("modelKey", modelKey);
            if (schemaHash != null) map.put("schemaHash", schemaHash);
            map.put("tsMs", Instant.now().toEpochMilli());

            MlPredictResponse r = c.predict(new MlPredictRequest(map));

            if (r == null) return MlPrediction.fail("null_response");
            if (!r.isOk()) return MlPrediction.fail("not_ok");

            Double probaObj = r.getProba();
            if (probaObj == null) return MlPrediction.fail("proba_null");

            double p = probaObj;
            if (!Double.isFinite(p)) p = 0.0;

            // single-proba -> buy/sell (бинарная модель)
            double pBuy = clamp01(p);
            double pSell = clamp01(1.0 - pBuy);

            // modelVersion может отсутствовать — возвращаем null
            return MlPrediction.ok(pBuy, pSell, null);

        } catch (Exception e) {
            return MlPrediction.fail("predict_exception:" + e.getClass().getSimpleName());
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

        // 1) isOk()
        try {
            Method m = h.getClass().getMethod("isOk");
            Object v = m.invoke(h);
            if (v instanceof Boolean b) return b;
        } catch (Exception ignored) {}

        // 2) getOk()
        try {
            Method m = h.getClass().getMethod("getOk");
            Object v = m.invoke(h);
            if (v instanceof Boolean b) return b;
        } catch (Exception ignored) {}

        // 3) field ok
        try {
            Field f = h.getClass().getDeclaredField("ok");
            f.setAccessible(true);
            Object v = f.get(h);
            if (v instanceof Boolean b) return b;
        } catch (Exception ignored) {}

        return false;
    }

    private static double clamp01(double v) {
        if (!Double.isFinite(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}