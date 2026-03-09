package com.chicu.aitradebot.ai.ml;

import com.chicu.aitradebot.ai.ml.dto.MlHealthResponse;
import com.chicu.aitradebot.ai.ml.dto.MlPredictRequest;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlGateway {

    private static final Set<String> META_KEYS = Set.of(
            "chatId",
            "strategyType",
            "strategy",
            "symbol",
            "exchange",
            "network",
            "timeframe",
            "modelKey",
            "schemaHash",
            "featureOrder",
            "featureSchema",
            "schema",
            "schemaFields",
            "ts",
            "tsMs"
    );

    /**
     * Канонический порядок для WINDOW_SCALPING.
     * Если какие-то поля отсутствуют — они просто будут пропущены.
     */
    private static final List<String> WINDOW_SCALPING_CANONICAL_ORDER = List.of(
            "windowSize",
            "lastPrice",
            "price",
            "low",
            "high",
            "range",
            "rangePct",
            "volatilityPct",
            "pos01",
            "posPct",
            "lowZone01",
            "highZone01",
            "diffPctForEntry",
            "retWindowPct",
            "momentum1",
            "smaFastRel",
            "smaSlowRel"
    );

    private static final List<String> WINDOW_SCALPING_REQUIRED_FEATURES = List.of(
            "momentum1",
            "volatilityPct",
            "smaFastRel",
            "smaSlowRel",
            "lastPrice"
    );

    private final MlProperties props;

    /**
     * MlClient создаётся только при ml.enabled=true.
     */
    private final ObjectProvider<MlClient> clientProvider;

    /**
     * Нужен для modelKey/schemaHash/timeframe из StrategySettings,
     * чтобы predict всегда шёл в правильную модель и с правильной схемой.
     */
    private final StrategySettingsService strategySettingsService;

    /**
     * Анти-спам по предупреждениям.
     */
    private final Map<String, Long> warnThrottle = new ConcurrentHashMap<>();

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
    // Predict API
    // =====================================================

    public MlPredictResponse predict(Map<String, Object> features) {
        return predictInternal(null, null, null, null, null, null, features, null);
    }

    public MlPredictResponse predictWindowScalping(Long chatId,
                                                   String symbol,
                                                   Map<String, Object> features,
                                                   Instant ts) {
        return predictInternal(StrategyType.WINDOW_SCALPING, chatId, symbol, null, null, null, features, ts);
    }

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

        Map<String, Object> raw = (features != null) ? new HashMap<>(features) : new HashMap<>();

        if (chatId == null) {
            chatId = extractLong(raw.get("chatId"));
        }

        if (type == null) {
            type = parseStrategyType(raw.get("strategyType"));
            if (type == null) {
                type = parseStrategyType(raw.get("strategy"));
            }
        }

        if (symbol == null || symbol.isBlank()) {
            symbol = extractString(raw.get("symbol"));
        }

        if (timeframe == null || timeframe.isBlank()) {
            timeframe = extractString(raw.get("timeframe"));
        }

        if (modelKey == null || modelKey.isBlank()) {
            modelKey = extractString(raw.get("modelKey"));
        }

        if (schemaHash == null || schemaHash.isBlank()) {
            schemaHash = extractString(raw.get("schemaHash"));
        }

        StrategySettings ss = resolveStrategySettings(chatId, type);

        if (ss != null) {
            if ((symbol == null || symbol.isBlank()) && ss.getSymbol() != null) {
                symbol = ss.getSymbol();
            }
            if ((timeframe == null || timeframe.isBlank()) && ss.getTimeframe() != null) {
                timeframe = ss.getTimeframe();
            }
            if ((modelKey == null || modelKey.isBlank()) && ss.getMlModelKey() != null) {
                modelKey = ss.getMlModelKey();
            }
            if ((schemaHash == null || schemaHash.isBlank()) && ss.getMlSchemaHash() != null) {
                schemaHash = ss.getMlSchemaHash();
            }
        }

        String symbolNorm = normUpper(symbol);
        String timeframeNorm = normLower(timeframe);
        String modelKeyNorm = blankToNull(modelKey);
        String schemaHashNorm = blankToNull(schemaHash);

        long tsMs = (ts != null ? ts.toEpochMilli() : System.currentTimeMillis());

        // -----------------------------------------------------
        // 1) Нормализуем и очищаем features
        // -----------------------------------------------------
        Map<String, Object> normalizedFeatures = normalizeFeatures(raw);

        if (normalizedFeatures.isEmpty()) {
            return MlPredictResponse.fail("no_features");
        }

        // -----------------------------------------------------
        // 2) Ранняя проверка обязательных фич
        // -----------------------------------------------------
        List<String> missing = validateRequiredFeatures(type, normalizedFeatures);
        if (!missing.isEmpty()) {
            String reason = "missing_features:" + String.join(",", missing);
            warnOnce(buildWarnKey(type, chatId, symbolNorm, reason), 15_000,
                    "🧠 ML predict отклонён до sidecar: не хватает фич | type={} chatId={} symbol={} missing={}",
                    type, chatId, symbolNorm, String.join(",", missing));
            return MlPredictResponse.fail(reason);
        }

        // -----------------------------------------------------
        // 3) Строим стабильный feature order
        // -----------------------------------------------------
        List<String> incomingOrder = extractFeatureOrder(raw);
        List<String> finalFeatureOrder = buildStableFeatureOrder(type, normalizedFeatures, incomingOrder);

        if (finalFeatureOrder.isEmpty()) {
            return MlPredictResponse.fail("feature_order_empty");
        }

        // -----------------------------------------------------
        // 4) Упорядочиваем map строго в order
        // -----------------------------------------------------
        LinkedHashMap<String, Object> orderedFeatures = reorderFeatures(normalizedFeatures, finalFeatureOrder);

        // -----------------------------------------------------
        // 5) Считаем hash схемы и валидируем
        // -----------------------------------------------------
        String computedSchemaHash = computeFeatureOrderHash(finalFeatureOrder);

        if (schemaHashNorm != null && !schemaHashNorm.equalsIgnoreCase(computedSchemaHash)) {
            String reason = "schema_hash_mismatch";
            warnOnce(buildWarnKey(type, chatId, symbolNorm, reason), 15_000,
                    "🧠 ML schema mismatch | type={} chatId={} symbol={} provided={} computed={}",
                    type, chatId, symbolNorm, schemaHashNorm, computedSchemaHash);
            return MlPredictResponse.fail(reason + ":provided=" + schemaHashNorm + ",computed=" + computedSchemaHash);
        }

        // если хэш не пришёл — используем вычисленный
        schemaHashNorm = computedSchemaHash;

        // -----------------------------------------------------
        // 6) Собираем request
        // -----------------------------------------------------
        MlPredictRequest req = new MlPredictRequest();
        req.setChatId(chatId);
        req.setStrategyType(type != null ? type.name() : null);
        req.setSymbol(symbolNorm);
        req.setTimeframe(timeframeNorm);
        req.setModelKey(modelKeyNorm);
        req.setSchemaHash(schemaHashNorm);
        req.setTsMs(tsMs);
        req.setFeatureOrder(finalFeatureOrder);
        req.setFeatures(orderedFeatures);

        try {
            MlPredictResponse r = c.predict(req);
            if (r == null) return MlPredictResponse.fail("predict_null");

            if (r.isOk() && (r.getProba() == null || !Double.isFinite(r.getProba()))) {
                return MlPredictResponse.fail("predict_no_proba");
            }

            if (!r.isOk()) {
                String err = safeStr(r.getError());

                if (isMissingFeaturesError(err)) {
                    warnOnce(buildWarnKey(type, chatId, symbolNorm, "missing_features"), 15_000,
                            "🧠 ML sidecar отклонил predict: missing_features | type={} chatId={} symbol={} err={}",
                            type, chatId, symbolNorm, err);
                } else if (isSchemaMismatchError(err)) {
                    warnOnce(buildWarnKey(type, chatId, symbolNorm, "schema_mismatch"), 15_000,
                            "🧠 ML sidecar отклонил predict: schema mismatch | type={} chatId={} symbol={} err={}",
                            type, chatId, symbolNorm, err);
                } else if (isModelFormatError(err)) {
                    warnOnce(buildWarnKey(type, chatId, symbolNorm, "model_format"), 30_000,
                            "🧠 ML sidecar: проблема формата модели | type={} chatId={} symbol={} err={}",
                            type, chatId, symbolNorm, err);
                } else if (isNeutralStreakError(err)) {
                    warnOnce(buildWarnKey(type, chatId, symbolNorm, "neutral_streak"), 30_000,
                            "🧠 ML sidecar: neutral-streak | type={} chatId={} symbol={} err={}",
                            type, chatId, symbolNorm, err);
                } else {
                    warnOnce(buildWarnKey(type, chatId, symbolNorm, "predict_not_ok"), 15_000,
                            "🧠 ML predict вернул not_ok | type={} chatId={} symbol={} err={}",
                            type, chatId, symbolNorm, err);
                }
            }

            return r;

        } catch (Exception e) {
            String msg = "predict_failed: " + safeMsg(e);
            warnOnce(buildWarnKey(type, chatId, symbolNorm, "predict_exception"), 15_000,
                    "🧠 ML predict exception | type={} chatId={} symbol={} err={}",
                    type, chatId, symbolNorm, msg);
            return MlPredictResponse.fail(msg);
        }
    }

    // =====================================================
    // Settings
    // =====================================================

    private StrategySettings resolveStrategySettings(Long chatId, StrategyType type) {
        if (chatId == null || chatId <= 0 || type == null || strategySettingsService == null) {
            return null;
        }
        try {
            return strategySettingsService.getOrCreate(chatId, type);
        } catch (Exception e) {
            log.debug("ML resolveStrategySettings failed chatId={} type={} err={}", chatId, type, e.toString());
            return null;
        }
    }

    // =====================================================
    // Feature order / schema
    // =====================================================

    private static List<String> extractFeatureOrder(Map<String, Object> features) {
        if (features == null || features.isEmpty()) return null;

        Object v = features.get("featureOrder");
        if (v == null) v = features.get("featureSchema");
        if (v == null) v = features.get("schema");
        if (v == null) v = features.get("schemaFields");

        if (!(v instanceof Iterable<?> iterable)) return null;

        List<String> out = new ArrayList<>();
        for (Object o : iterable) {
            String s = extractString(o);
            if (s != null && !META_KEYS.contains(s)) {
                out.add(s);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<String> buildStableFeatureOrder(StrategyType type,
                                                        Map<String, Object> normalizedFeatures,
                                                        List<String> incomingOrder) {

        LinkedHashSet<String> ordered = new LinkedHashSet<>();

        // 1. Сначала берём внешний порядок, если он пришёл
        if (incomingOrder != null) {
            for (String name : incomingOrder) {
                if (name != null && normalizedFeatures.containsKey(name)) {
                    ordered.add(name);
                }
            }
        }

        // 2. Для WINDOW_SCALPING — канонический порядок
        if (type == StrategyType.WINDOW_SCALPING) {
            for (String name : WINDOW_SCALPING_CANONICAL_ORDER) {
                if (normalizedFeatures.containsKey(name)) {
                    ordered.add(name);
                }
            }
        }

        // 3. Остальные поля — по алфавиту, чтобы порядок всегда был одинаковым
        List<String> rest = new ArrayList<>(normalizedFeatures.keySet());
        rest.sort(Comparator.naturalOrder());

        for (String name : rest) {
            ordered.add(name);
        }

        return new ArrayList<>(ordered);
    }

    private static LinkedHashMap<String, Object> reorderFeatures(Map<String, Object> features,
                                                                 List<String> featureOrder) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (String key : featureOrder) {
            if (features.containsKey(key)) {
                out.put(key, features.get(key));
            }
        }
        return out;
    }

    private static String computeFeatureOrderHash(List<String> featureOrder) {
        try {
            String payload = String.join("|", featureOrder);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // fallback практически не должен понадобиться
            return Integer.toHexString(featureOrder.hashCode());
        }
    }

    // =====================================================
    // Feature validation / normalization
    // =====================================================

    private static List<String> validateRequiredFeatures(StrategyType type,
                                                         Map<String, Object> features) {
        if (type != StrategyType.WINDOW_SCALPING) {
            return Collections.emptyList();
        }

        List<String> missing = new ArrayList<>();
        for (String req : WINDOW_SCALPING_REQUIRED_FEATURES) {
            if (!features.containsKey(req) || features.get(req) == null) {
                missing.add(req);
            }
        }
        return missing;
    }

    private static Map<String, Object> normalizeFeatures(Map<String, Object> in) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (in == null || in.isEmpty()) return out;

        for (Map.Entry<String, Object> e : in.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();

            if (k == null || k.isBlank()) continue;
            if (META_KEYS.contains(k)) continue;

            Object nv = normalizeValue(v);
            out.put(k, nv);
        }
        return out;
    }

    private static Object normalizeValue(Object v) {
        if (v == null) return null;

        if (v instanceof Enum<?> en) return en.name();
        if (v instanceof Instant inst) return inst.toEpochMilli();

        if (v instanceof Double d) {
            if (!Double.isFinite(d)) return null;
            return d;
        }
        if (v instanceof Float f) {
            if (!Float.isFinite(f)) return null;
            return (double) f;
        }

        return v;
    }

    // =====================================================
    // Parsing helpers
    // =====================================================

    private static StrategyType parseStrategyType(Object v) {
        String s = extractString(v);
        if (s == null) return null;
        try {
            return StrategyType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private static Long extractLong(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof Number n) return n.longValue();
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractString(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String normUpper(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String normLower(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // =====================================================
    // Error classifiers
    // =====================================================

    private static boolean isModelFormatError(String err) {
        if (err == null) return false;
        String s = err.toLowerCase(Locale.ROOT);

        if (s.contains("predict_proba") && s.contains("dict")) return true;
        if (s.contains("xgboost") && (s.contains("save_model") || s.contains("serialized model"))) return true;

        return s.contains("model") && (s.contains("load") || s.contains("deserialize") || s.contains("pickle"));
    }

    private static boolean isNeutralStreakError(String err) {
        if (err == null) return false;
        String s = err.toLowerCase(Locale.ROOT);
        return s.contains("neutral_streak");
    }

    private static boolean isMissingFeaturesError(String err) {
        if (err == null) return false;
        return err.toLowerCase(Locale.ROOT).contains("missing_features");
    }

    private static boolean isSchemaMismatchError(String err) {
        if (err == null) return false;
        String s = err.toLowerCase(Locale.ROOT);
        return s.contains("schema_hash_mismatch") || s.contains("feature hash mismatch") || s.contains("schema mismatch");
    }

    // =====================================================
    // Warn throttle
    // =====================================================

    private void warnOnce(String key, long throttleMs, String template, Object... args) {
        long now = System.currentTimeMillis();
        Long prev = warnThrottle.get(key);

        if (prev != null && now - prev < throttleMs) {
            return;
        }

        warnThrottle.put(key, now);
        log.warn(template, args);
    }

    private static String buildWarnKey(StrategyType type,
                                       Long chatId,
                                       String symbol,
                                       String suffix) {
        return String.valueOf(type) + "|" + chatId + "|" + symbol + "|" + suffix;
    }

    // =====================================================
    // Misc
    // =====================================================

    private static String safeMsg(Throwable e) {
        if (e == null) return "null";
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        return m;
    }

    private static String safeStr(String s) {
        return s == null ? "null" : s;
    }
}