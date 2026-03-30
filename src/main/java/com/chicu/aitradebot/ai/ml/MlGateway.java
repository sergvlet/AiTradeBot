package com.chicu.aitradebot.ai.ml;

import com.chicu.aitradebot.ai.ml.dto.MlHealthResponse;
import com.chicu.aitradebot.ai.ml.dto.MlPredictRequest;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
import java.util.Objects;
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

    private static final StrategyFeatureSpec WINDOW_SCALPING_SPEC = new StrategyFeatureSpec(
            List.of(
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
                    "smaSlowRel",
                    "entryFromLowPct",
                    "entryFromHighPct",
                    "minRangePct",
                    "takeProfitPct",
                    "stopLossPct",
                    "autoTpSlEnabled",
                    "autoSlFromRangeFactor",
                    "autoTpFromRangeFactor",
                    "autoMinRiskReward",
                    "autoSlMinPct",
                    "autoSlMaxPct",
                    "autoTpMinPct",
                    "autoTpMaxPct",
                    "autoTpMlBoostFactor",
                    "autoTpWeakSignalFactor",
                    "maxSpreadPct"
            ),
            List.of(
                    "momentum1",
                    "volatilityPct",
                    "smaFastRel",
                    "smaSlowRel",
                    "lastPrice"
            )
    );

    private static final Map<StrategyType, StrategyFeatureSpec> FEATURE_SPECS = Map.of(
            StrategyType.WINDOW_SCALPING, WINDOW_SCALPING_SPEC
    );

    private final MlProperties props;
    private final ObjectProvider<MlClient> clientProvider;
    private final StrategySettingsService strategySettingsService;
    private final Map<String, Long> warnThrottle = new ConcurrentHashMap<>();

    private static final class StrategyFeatureSpec {
        private final List<String> canonicalOrder;
        private final List<String> requiredFeatures;

        private StrategyFeatureSpec(List<String> canonicalOrder, List<String> requiredFeatures) {
            this.canonicalOrder = canonicalOrder != null ? List.copyOf(canonicalOrder) : List.of();
            this.requiredFeatures = requiredFeatures != null ? List.copyOf(requiredFeatures) : List.of();
        }
    }

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

    public MlPredictResponse predict(Map<String, Object> features) {
        return predictInternal(null, null, null, null, null, null, null, features, null);
    }

    public MlPredictResponse predictWindowScalping(Long chatId,
                                                   String symbol,
                                                   Map<String, Object> features,
                                                   Instant ts) {
        return predictInternal(StrategyType.WINDOW_SCALPING, chatId, symbol, null, null, null, null, features, ts);
    }

    public MlPredictResponse predict(StrategyType type,
                                     Long chatId,
                                     String symbol,
                                     Map<String, Object> features,
                                     Instant ts) {
        return predictInternal(type, chatId, symbol, null, null, null, null, features, ts);
    }

    private MlPredictResponse predictInternal(StrategyType type,
                                              Long chatId,
                                              String symbol,
                                              String timeframe,
                                              String exchange,
                                              String network,
                                              String modelKey,
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
            if (type == null) type = parseStrategyType(raw.get("strategy"));
        }
        if (symbol == null || symbol.isBlank()) {
            symbol = extractString(raw.get("symbol"));
        }
        if (timeframe == null || timeframe.isBlank()) {
            timeframe = extractString(raw.get("timeframe"));
        }
        if (exchange == null || exchange.isBlank()) {
            exchange = extractString(raw.get("exchange"));
        }
        if (network == null || network.isBlank()) {
            network = extractString(raw.get("network"));
        }
        if (modelKey == null || modelKey.isBlank()) {
            modelKey = extractString(raw.get("modelKey"));
        }

        StrategySettings ss = resolveStrategySettings(chatId, type);
        String storedModelKey = null;
        if (ss != null) {
            if ((symbol == null || symbol.isBlank()) && ss.getSymbol() != null) {
                symbol = ss.getSymbol();
            }
            if ((timeframe == null || timeframe.isBlank()) && ss.getTimeframe() != null) {
                timeframe = ss.getTimeframe();
            }
            if ((exchange == null || exchange.isBlank()) && ss.getExchangeName() != null) {
                exchange = String.valueOf(ss.getExchangeName());
            }
            if ((network == null || network.isBlank()) && ss.getNetworkType() != null) {
                network = String.valueOf(ss.getNetworkType());
            }
            storedModelKey = blankToNull(ss.getMlModelKey());
        }

        String symbolNorm = normUpper(symbol);
        String timeframeNorm = normLower(timeframe);
        String exchangeNorm = normUpper(exchange);
        String networkNorm = normUpper(network);
        String contextModelKey = buildContextModelKey(type, exchangeNorm, networkNorm, symbolNorm, timeframeNorm);
        if ((modelKey == null || modelKey.isBlank()) && storedModelKey != null) {
            if (isCompatibleModelKey(storedModelKey, type, exchangeNorm, networkNorm, symbolNorm, timeframeNorm)) {
                modelKey = storedModelKey;
            } else {
                warnOnce(buildWarnKey(type, chatId, symbolNorm, "stale_model_key"), 60_000,
                        "🧠 ML ignore stale settings modelKey | type={} chatId={} symbol={} storedModelKey={} contextModelKey={}",
                        type, chatId, symbolNorm, storedModelKey, contextModelKey);
            }
        }
        long tsMs = (ts != null ? ts.toEpochMilli() : System.currentTimeMillis());

        LinkedHashMap<String, Object> normalizedFeatures = normalizeFeatures(type, raw);
        if (normalizedFeatures.isEmpty()) {
            return MlPredictResponse.fail("no_features");
        }

        List<String> missing = validateRequiredFeatures(type, normalizedFeatures);
        if (!missing.isEmpty()) {
            String reason = "missing_features:" + String.join(",", missing);
            warnOnce(buildWarnKey(type, chatId, symbolNorm, reason), 15_000,
                    "🧠 ML predict отклонён до sidecar: не хватает фич | type={} chatId={} symbol={} missing={}",
                    type, chatId, symbolNorm, String.join(",", missing));
            return MlPredictResponse.fail(reason);
        }

        List<String> incomingOrder = extractFeatureOrder(raw);
        List<String> finalFeatureOrder = buildStableFeatureOrder(type, normalizedFeatures, incomingOrder);
        if (finalFeatureOrder.isEmpty()) {
            return MlPredictResponse.fail("feature_order_empty");
        }

        LinkedHashMap<String, Object> orderedFeatures = reorderFeatures(normalizedFeatures, finalFeatureOrder);
        String computedFeatureOrderHash = computeFeatureOrderHash(finalFeatureOrder);

        String schemaHashNorm = extractString(raw.get("schemaHash"));
        if (schemaHashNorm == null && ss != null && ss.getMlSchemaHash() != null) {
            schemaHashNorm = ss.getMlSchemaHash();
        }

        if (schemaHashNorm != null && !schemaHashNorm.equalsIgnoreCase(computedFeatureOrderHash)) {
            String reason = "featureOrder_hash_mismatch provided=" + schemaHashNorm + " req=" + computedFeatureOrderHash;
            warnOnce(buildWarnKey(type, chatId, symbolNorm, "featureOrder_hash_mismatch"), 15_000,
                    "🧠 ML feature order mismatch до sidecar | type={} chatId={} symbol={} modelKey={} provided={} req={} order={}",
                    type, chatId, symbolNorm, modelKey, schemaHashNorm, computedFeatureOrderHash, String.join(",", finalFeatureOrder));
            return MlPredictResponse.fail(reason);
        }
        schemaHashNorm = computedFeatureOrderHash;

        String modelKeyNorm = blankToNull(modelKey);
        if (modelKeyNorm == null) {
            modelKeyNorm = contextModelKey;
        }

        MlPredictRequest req = buildPredictRequest(
                chatId,
                type,
                symbolNorm,
                timeframeNorm,
                modelKeyNorm,
                schemaHashNorm,
                tsMs,
                finalFeatureOrder,
                orderedFeatures
        );

        MlPredictResponse primary = executePredict(c, req, type, chatId, symbolNorm, modelKeyNorm, schemaHashNorm);
        if (shouldRetryWithoutSpecificModelKey(primary, modelKeyNorm)) {
            warnOnce(buildWarnKey(type, chatId, symbolNorm, "predict_retry_default_model"), 15_000,
                    "🧠 ML retry without fixed modelKey | type={} chatId={} symbol={} failedModelKey={} err={}",
                    type, chatId, symbolNorm, modelKeyNorm, safeStr(primary != null ? primary.getError() : null));

            MlPredictRequest retryReq = buildPredictRequest(
                    chatId,
                    type,
                    symbolNorm,
                    timeframeNorm,
                    null,
                    schemaHashNorm,
                    tsMs,
                    finalFeatureOrder,
                    orderedFeatures
            );

            MlPredictResponse retry = executePredict(c, retryReq, type, chatId, symbolNorm, null, schemaHashNorm);
            if (retry != null && retry.isOk()) {
                log.info("🧠 ML fallback predict OK | type={} chatId={} symbol={} contextModelKey={} fallbackModelKey={} proba={}",
                        type, chatId, symbolNorm, modelKeyNorm, blankToNull(retry.getModelKey()), retry.getProba());
                return retry;
            }
            return retry != null ? retry : primary;
        }
        return primary;
    }

    private MlPredictRequest buildPredictRequest(Long chatId,
                                                StrategyType type,
                                                String symbolNorm,
                                                String timeframeNorm,
                                                String modelKey,
                                                String schemaHash,
                                                long tsMs,
                                                List<String> featureOrder,
                                                LinkedHashMap<String, Object> orderedFeatures) {
        MlPredictRequest req = new MlPredictRequest();
        req.setChatId(chatId);
        req.setStrategyType(type != null ? type.name() : null);
        req.setSymbol(symbolNorm);
        req.setTimeframe(timeframeNorm);
        req.setModelKey(blankToNull(modelKey));
        req.setSchemaHash(schemaHash);
        req.setTsMs(tsMs);
        req.setFeatureOrder(featureOrder);
        req.setFeatures(orderedFeatures);
        return req;
    }

    private MlPredictResponse executePredict(MlClient client,
                                             MlPredictRequest req,
                                             StrategyType type,
                                             Long chatId,
                                             String symbolNorm,
                                             String logModelKey,
                                             String schemaHashNorm) {
        try {
            MlPredictResponse r = client.predict(req);
            if (r == null) return MlPredictResponse.fail("predict_null");
            if (r.isOk() && (r.getProba() == null || !Double.isFinite(r.getProba()))) {
                return MlPredictResponse.fail("predict_no_proba");
            }
            if (!r.isOk()) {
                warnOnce(buildWarnKey(type, chatId, symbolNorm, safeStr(r.getError())), 15_000,
                        "🧠 ML predict вернул not_ok | type={} chatId={} symbol={} modelKey={} err={} reqHash={}",
                        type, chatId, symbolNorm, blankToNull(logModelKey), safeStr(r.getError()), schemaHashNorm);
            }
            return r;
        } catch (Exception e) {
            String msg = "predict_failed: " + safeMsg(e);
            warnOnce(buildWarnKey(type, chatId, symbolNorm, "predict_exception"), 15_000,
                    "🧠 ML predict exception | type={} chatId={} symbol={} modelKey={} err={}",
                    type, chatId, symbolNorm, blankToNull(logModelKey), msg);
            return MlPredictResponse.fail(msg);
        }
    }

    private static boolean shouldRetryWithoutSpecificModelKey(MlPredictResponse response, String modelKey) {
        if (blankToNull(modelKey) == null || response == null || response.isOk()) {
            return false;
        }
        String err = normLower(response.getError());
        if (err == null) {
            return false;
        }
        return err.contains("no_model")
                || err.contains("model_not_found")
                || err.contains("missing_model")
                || err.contains("load_error");
    }

    private static boolean isCompatibleModelKey(String modelKey,
                                                StrategyType type,
                                                String exchange,
                                                String network,
                                                String symbol,
                                                String timeframe) {
        String value = blankToNull(modelKey);
        if (value == null) return false;

        String[] parts = value.split(":");
        if (parts.length >= 5) {
            return equalsIgnoreCase(parts[0], type != null ? type.name() : null)
                    && equalsIgnoreCase(parts[1], exchange)
                    && equalsIgnoreCase(parts[2], network)
                    && equalsIgnoreCase(parts[3], symbol)
                    && equalsIgnoreCase(parts[4], timeframe);
        }
        if (parts.length == 3) {
            return equalsIgnoreCase(parts[0], type != null ? type.name() : null)
                    && equalsIgnoreCase(parts[1], symbol)
                    && equalsIgnoreCase(parts[2], timeframe);
        }
        return false;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        String left = blankToNull(a);
        String right = blankToNull(b);
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        return left.equalsIgnoreCase(right);
    }

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

    public static String buildContextModelKey(StrategyType type,
                                              String exchange,
                                              String network,
                                              String symbol,
                                              String timeframe) {
        String st = type != null ? type.name() : "GLOBAL";
        String ex = blankToDefault(normUpper(exchange), "NA");
        String net = blankToDefault(normUpper(network), "NA");
        String sym = blankToDefault(normUpper(symbol), "NA");
        String tf = blankToDefault(normLower(timeframe), "na");
        return st + ":" + ex + ":" + net + ":" + sym + ":" + tf;
    }

    private static List<String> extractFeatureOrder(Map<String, Object> features) {
        if (features == null || features.isEmpty()) return null;
        Object v = features.get("featureOrder");
        if (v == null) v = features.get("featureSchema");
        if (v == null) v = features.get("schema");
        if (v == null) v = features.get("schemaFields");
        if (v == null) return null;

        List<String> out = new ArrayList<>();
        if (v instanceof Iterable<?> iterable) {
            for (Object o : iterable) {
                String s = normalizeFeatureName(extractString(o));
                if (s != null && !META_KEYS.contains(s)) out.add(s);
            }
        } else {
            String raw = extractString(v);
            if (raw != null) {
                String prepared = raw.replace(';', ',').replace('|', ',');
                for (String part : prepared.split(",")) {
                    String s = normalizeFeatureName(part);
                    if (s != null && !META_KEYS.contains(s)) out.add(s);
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<String> buildStableFeatureOrder(StrategyType type,
                                                        Map<String, Object> normalizedFeatures,
                                                        List<String> incomingOrder) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (incomingOrder != null) {
            for (String name : incomingOrder) {
                if (name != null && normalizedFeatures.containsKey(name)) ordered.add(name);
            }
        }
        StrategyFeatureSpec spec = FEATURE_SPECS.get(type);
        if (spec != null) {
            for (String name : spec.canonicalOrder) {
                if (normalizedFeatures.containsKey(name)) ordered.add(name);
            }
        }
        List<String> rest = new ArrayList<>(normalizedFeatures.keySet());
        rest.sort(Comparator.naturalOrder());
        ordered.addAll(rest);
        return new ArrayList<>(ordered);
    }

    private static LinkedHashMap<String, Object> reorderFeatures(Map<String, Object> features,
                                                                 List<String> featureOrder) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (String key : featureOrder) {
            if (features.containsKey(key)) out.put(key, features.get(key));
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
            return Integer.toHexString(featureOrder.hashCode());
        }
    }

    private static List<String> validateRequiredFeatures(StrategyType type,
                                                         Map<String, Object> features) {
        StrategyFeatureSpec spec = FEATURE_SPECS.get(type);
        if (spec == null || spec.requiredFeatures.isEmpty()) return Collections.emptyList();
        List<String> missing = new ArrayList<>();
        for (String req : spec.requiredFeatures) {
            if (!features.containsKey(req) || features.get(req) == null) missing.add(req);
        }
        return missing;
    }

    private static LinkedHashMap<String, Object> normalizeFeatures(StrategyType type, Map<String, Object> in) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (in == null || in.isEmpty()) return out;
        for (Map.Entry<String, Object> e : in.entrySet()) {
            String k = normalizeFeatureName(e.getKey());
            if (k == null || META_KEYS.contains(k)) continue;
            out.put(k, normalizeValue(e.getValue()));
        }
        applyStrategyAliases(type, out);
        return out;
    }

    private static void applyStrategyAliases(StrategyType type, LinkedHashMap<String, Object> out) {
        if (out == null || out.isEmpty()) return;
        if (type == StrategyType.WINDOW_SCALPING) {
            if (!out.containsKey("lastPrice") && out.containsKey("price")) out.put("lastPrice", out.get("price"));
            if (!out.containsKey("price") && out.containsKey("lastPrice")) out.put("price", out.get("lastPrice"));
        }
    }

    private static Object normalizeValue(Object v) {
        if (v == null) return null;
        if (v instanceof Enum<?> en) return en.name();
        if (v instanceof Instant inst) return inst.toEpochMilli();
        if (v instanceof Double d) return Double.isFinite(d) ? d : null;
        if (v instanceof Float f) return Float.isFinite(f) ? (double) f : null;
        if (v instanceof BigDecimal bd) return bd.stripTrailingZeros();
        return v;
    }

    private static String normalizeFeatureName(String key) {
        if (key == null) return null;
        String s = key.trim();
        return s.isEmpty() ? null : s;
    }

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

    private static String blankToDefault(String s, String fallback) {
        String v = blankToNull(s);
        return v != null ? v : fallback;
    }

    private void warnOnce(String key, long throttleMs, String template, Object... args) {
        long now = System.currentTimeMillis();
        Long prev = warnThrottle.get(key);
        if (prev != null && now - prev < throttleMs) return;
        warnThrottle.put(key, now);
        log.warn(template, args);
    }

    private static String buildWarnKey(StrategyType type, Long chatId, String symbol, String suffix) {
        return String.valueOf(type) + "|" + chatId + "|" + symbol + "|" + suffix;
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
}
