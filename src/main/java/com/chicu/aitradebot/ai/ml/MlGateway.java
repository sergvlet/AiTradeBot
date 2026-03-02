package com.chicu.aitradebot.ai.ml;

import com.chicu.aitradebot.ai.ml.dto.MlHealthResponse;
import com.chicu.aitradebot.ai.ml.dto.MlPredictRequest;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlGateway {

    private final MlProperties props;

    /**
     * ✅ MlClient создаётся только при ml.enabled=true.
     * Поэтому берём через ObjectProvider, иначе контекст падает при ml.enabled=false.
     */
    private final ObjectProvider<MlClient> clientProvider;

    public boolean isEnabled() {
        if (props == null || !props.isEnabled()) return false;
        return clientProvider != null && clientProvider.getIfAvailable() != null;
    }

    public MlHealthResponse health() {
        if (props == null || !props.isEnabled()) {
            return MlHealthResponse.fail("ML disabled (ml.enabled=false)");
        }

        MlClient c = clientProvider != null ? clientProvider.getIfAvailable() : null;
        if (c == null) {
            return MlHealthResponse.fail("ML client bean not found (ml.enabled=false?)");
        }

        try {
            MlHealthResponse r = c.health();
            return (r != null) ? r : MlHealthResponse.fail("health_null");
        } catch (Exception e) {
            return MlHealthResponse.fail("health_failed: " + safeMsg(e));
        }
    }

    // =====================================================
    // Predict API (backward + typed)
    // =====================================================

    /** ✅ Backward-compatible: старый вход (только features). */
    public MlPredictResponse predict(Map<String, Object> features) {
        return predictInternal(null, null, null, null, null, null, features, null);
    }

    /** ✅ Под это твоя стратегия раньше искала "predictWindowScalping" (4 args). */
    public MlPredictResponse predictWindowScalping(Long chatId,
                                                   String symbol,
                                                   Map<String, Object> features,
                                                   Instant ts) {
        return predictInternal(StrategyType.WINDOW_SCALPING, chatId, symbol, null, null, null, features, ts);
    }

    /** ✅ Универсальный predict для стратегий. */
    public MlPredictResponse predict(StrategyType type,
                                     Long chatId,
                                     String symbol,
                                     Map<String, Object> features,
                                     Instant ts) {
        return predictInternal(type, chatId, symbol, null, null, null, features, ts);
    }

    // =====================================================
    // Internal
    // =====================================================

    private MlPredictResponse predictInternal(StrategyType type,
                                              Long chatId,
                                              String symbol,
                                              String timeframe,
                                              String modelKey,
                                              String schemaHash,
                                              Map<String, Object> features,
                                              Instant ts) {

        if (props == null || !props.isEnabled()) {
            return MlPredictResponse.fail("ML disabled (ml.enabled=false)");
        }

        MlClient c = clientProvider != null ? clientProvider.getIfAvailable() : null;
        if (c == null) {
            return MlPredictResponse.fail("ML client bean not found (ml.enabled=false?)");
        }

        Map<String, Object> f = (features != null) ? new HashMap<>(features) : new HashMap<>();

        // ✅ нормализуем контекст в features (не перетираем существующее)
        if (chatId != null) f.putIfAbsent("chatId", chatId);

        if (type != null) {
            // новый ключ
            f.putIfAbsent("strategyType", type.name());
            // старый ключ (на всякий)
            f.putIfAbsent("strategy", type.name());
        }

        if (symbol != null && !symbol.isBlank()) {
            f.putIfAbsent("symbol", symbol.trim().toUpperCase(Locale.ROOT));
        }

        if (timeframe != null && !timeframe.isBlank()) {
            f.putIfAbsent("timeframe", timeframe.trim().toLowerCase(Locale.ROOT));
        }

        long tsMs = (ts != null ? ts.toEpochMilli() : System.currentTimeMillis());
        f.putIfAbsent("ts", tsMs);
        f.putIfAbsent("tsMs", tsMs);

        // ✅ нормализуем значения (Enum/Instant/BigDecimal и т.п.)
        f = normalizeFeatures(f);

        // ✅ top-level request тоже заполняем (это правильный контракт)
        MlPredictRequest req = new MlPredictRequest();
        req.setChatId(chatId);
        req.setStrategyType(type != null ? type.name() : null);
        req.setSymbol(symbol != null ? symbol.trim().toUpperCase(Locale.ROOT) : null);
        req.setTimeframe(timeframe != null ? timeframe.trim().toLowerCase(Locale.ROOT) : null);

        req.setModelKey(blankToNull(modelKey));
        req.setSchemaHash(blankToNull(schemaHash));
        req.setTsMs(tsMs);

        req.setFeatures(f);

        try {
            MlPredictResponse r = c.predict(req);
            if (r == null) return MlPredictResponse.fail("predict_null");

            // ✅ safety: если sidecar вернул ok=true, но proba нет — считаем fail
            if (r.isOk() && (r.getProba() == null || !Double.isFinite(r.getProba()))) {
                return MlPredictResponse.fail("predict_no_proba");
            }

            // ✅ если sidecar явно сказал, что модель/формат сломан — подсвечиваем это в логах
            if (!r.isOk() && isModelFormatError(r.getError())) {
                log.warn("🧠 ML model format error (sidecar): type={} chatId={} symbol={} err={}",
                        type, chatId, symbol, safeStr(r.getError()));
            }

            return r;

        } catch (Exception e) {
            String msg = "predict_failed: " + safeMsg(e);
            log.warn("🧠 ML predict failed type={} chatId={} symbol={} err={}", type, chatId, symbol, msg);
            return MlPredictResponse.fail(msg);
        }
    }

    private static Map<String, Object> normalizeFeatures(Map<String, Object> in) {
        Map<String, Object> out = new HashMap<>();
        if (in == null || in.isEmpty()) return out;

        for (Map.Entry<String, Object> e : in.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k == null || k.isBlank()) continue;

            Object nv = normalizeValue(v);
            out.put(k, nv);
        }
        return out;
    }

    private static Object normalizeValue(Object v) {
        if (v == null) return null;

        if (v instanceof Enum<?> en) return en.name();
        if (v instanceof Instant inst) return inst.toEpochMilli();

        // BigDecimal оставляем BigDecimal (Jackson нормально сериализует), но если кто-то сунул NaN/Inf — защитимся
        if (v instanceof Double d) {
            if (!Double.isFinite(d)) return null;
            return d;
        }
        if (v instanceof Float f) {
            if (!Float.isFinite(f)) return null;
            return f.doubleValue();
        }

        // числа/строки/булевы/мапы/листы — пусть идут как есть
        return v;
    }

    private static boolean isModelFormatError(String err) {
        if (err == null) return false;
        String s = err.toLowerCase(Locale.ROOT);

        // типичные симптомы твоего лога:
        // "'dict' object has no attribute 'predict_proba'"
        if (s.contains("predict_proba") && s.contains("dict")) return true;

        // предупреждение xgboost про старую сериализацию
        if (s.contains("xgboost") && (s.contains("save_model") || s.contains("serialized model"))) return true;

        // общие признаки сломанной загрузки модели
        return s.contains("model") && (s.contains("load") || s.contains("deserialize") || s.contains("pickle"));
    }

    private static String safeMsg(Throwable e) {
        if (e == null) return "null";
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        return m;
    }

    private static String safeStr(String s) {
        return s == null ? "null" : s;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}