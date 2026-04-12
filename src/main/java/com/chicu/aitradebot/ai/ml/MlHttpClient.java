package com.chicu.aitradebot.ai.ml;

import com.chicu.aitradebot.ai.ml.dto.MlHealthResponse;
import com.chicu.aitradebot.ai.ml.dto.MlPredictRequest;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
import com.chicu.aitradebot.ai.ml.dto.MlTrainRequest;
import com.chicu.aitradebot.ai.ml.dto.MlTrainResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class MlHttpClient implements MlClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long STARTUP_HEALTH_GRACE_MS = 20_000L;
    private static final long HEALTH_WARN_THROTTLE_MS = 15_000L;

    private final OkHttpClient http;
    private final ObjectMapper om;
    private final String baseUrl;
    private final String apiKey;

    private final long createdAtMs = System.currentTimeMillis();
    private final AtomicLong lastHealthWarnAtMs = new AtomicLong(0);

    public MlHttpClient(OkHttpClient http, ObjectMapper om, String baseUrl) {
        this(http, om, baseUrl, null);
    }

    public MlHttpClient(OkHttpClient http, ObjectMapper om, String baseUrl, String apiKey) {
        this.http = Objects.requireNonNull(http, "http");
        this.om = Objects.requireNonNull(om, "om");
        this.baseUrl = trimSlash(baseUrl);
        this.apiKey = (apiKey == null || apiKey.isBlank()) ? null : apiKey.trim();
    }

    @Override
    public MlHealthResponse health() {
        String url = baseUrl + "/health";
        Request req = withAuth(new Request.Builder().url(url).get()).build();
        return execute(req, MlHealthResponse.class, "health", "health");
    }

    @Override
    public MlPredictResponse predict(MlPredictRequest request) {
        MlPredictResponse invalid = validatePredictRequest(request);
        if (invalid != null) {
            return invalid;
        }

        String url = baseUrl + "/predict";
        String ctx = buildPredictContext(request);
        MlPredictResponse r = postJson(url, request, MlPredictResponse.class, "predict", ctx);

        if (r == null) {
            return MlPredictResponse.fail("predict_null");
        }

        if (r.isOk() && (r.getProba() == null || !Double.isFinite(r.getProba()))) {
            log.warn("🧠 ML predict: sidecar вернул ok=true, но proba отсутствует | {}", ctx);
            return MlPredictResponse.fail("predict_no_proba");
        }

        if (!r.isOk() && (r.getError() == null || r.getError().isBlank())) {
            r.setError("predict_not_ok");
        }

        return r;
    }

    @Override
    public MlTrainResponse train(MlTrainRequest request) {
        String url = baseUrl + "/train";
        return postJson(url, request, MlTrainResponse.class, "train", "train");
    }

    private Request.Builder withAuth(Request.Builder b) {
        if (apiKey != null) {
            b.header("X-API-KEY", apiKey);
        }
        b.header("Accept", "application/json");
        return b;
    }

    private <T> T postJson(String url, Object body, Class<T> responseType, String op, String ctx) {
        try {
            byte[] json = om.writeValueAsBytes(body);
            RequestBody rb = RequestBody.create(json, JSON);

            Request req = withAuth(new Request.Builder()
                    .url(url)
                    .post(rb)
                    .header("Content-Type", "application/json"))
                    .build();

            return execute(req, responseType, op, ctx);

        } catch (Exception e) {
            log.warn("🧠 ML {}: не удалось сериализовать request | {} | err={}", op, ctx, safeMsg(e));
            return errorResponse(responseType, "serialize_error: " + safeMsg(e));
        }
    }

    private <T> T execute(Request req, Class<T> responseType, String op, String ctx) {
        long ts = System.currentTimeMillis();

        try (Response resp = http.newCall(req).execute()) {
            long tookMs = System.currentTimeMillis() - ts;
            String body = resp.body() != null ? resp.body().string() : null;

            if (!resp.isSuccessful()) {
                String bodySafe = safe(body);
                String err = "http_" + resp.code() + (bodySafe != null && !bodySafe.isBlank() ? (": " + bodySafe) : "");

                if (isHealthOp(op)) {
                    logHealthProblem("HTTP " + resp.code(), tookMs, ctx, bodySafe);
                } else {
                    log.warn("🧠 ML {}: HTTP {} | tookMs={} | {} | body={}", op, resp.code(), tookMs, ctx, bodySafe);
                }

                return errorResponse(responseType, err);
            }

            if (body == null || body.isBlank()) {
                if (isHealthOp(op)) {
                    logHealthProblem("пустой body", tookMs, ctx, null);
                } else {
                    log.warn("🧠 ML {}: пустой body | tookMs={} | {}", op, tookMs, ctx);
                }
                return errorResponse(responseType, "empty_body");
            }

            T parsed = om.readValue(body, responseType);

            if (log.isDebugEnabled()) {
                log.debug("🧠 ML {}: OK | tookMs={} | {}", op, tookMs, ctx);
            }

            return parsed;

        } catch (IOException e) {
            long tookMs = System.currentTimeMillis() - ts;

            if (isHealthOp(op)) {
                logHealthProblem("IO ошибка", tookMs, ctx, safeMsg(e));
            } else {
                log.warn("🧠 ML {}: IO ошибка | tookMs={} | {} | err={}", op, tookMs, ctx, safeMsg(e));
            }

            return errorResponse(responseType, "io_error: " + safeMsg(e));

        } catch (Exception e) {
            long tookMs = System.currentTimeMillis() - ts;

            if (isHealthOp(op)) {
                logHealthProblem("ошибка разбора ответа", tookMs, ctx, safeMsg(e));
            } else {
                log.warn("🧠 ML {}: ошибка разбора ответа | tookMs={} | {} | err={}", op, tookMs, ctx, safeMsg(e));
            }

            return errorResponse(responseType, "parse_error: " + safeMsg(e));
        }
    }

    private void logHealthProblem(String problem, long tookMs, String ctx, String extra) {
        long now = System.currentTimeMillis();
        boolean startupGrace = (now - createdAtMs) < STARTUP_HEALTH_GRACE_MS;
        String suffix = (extra == null || extra.isBlank()) ? "" : " | err=" + extra;

        if (startupGrace) {
            if (log.isDebugEnabled()) {
                log.debug("🧠 ML health: {} | tookMs={} | {}{}", problem, tookMs, ctx, suffix);
            }
            return;
        }

        long prev = lastHealthWarnAtMs.get();
        if (now - prev < HEALTH_WARN_THROTTLE_MS && !log.isDebugEnabled()) {
            return;
        }
        lastHealthWarnAtMs.set(now);

        log.warn("🧠 ML health: {} | tookMs={} | {}{}", problem, tookMs, ctx, suffix);
    }

    private boolean isHealthOp(String op) {
        return "health".equalsIgnoreCase(op);
    }

    private MlPredictResponse validatePredictRequest(MlPredictRequest request) {
        if (request == null) {
            return MlPredictResponse.fail("predict_request_null");
        }

        Map<String, Object> features = request.getFeatures();
        if (features == null || features.isEmpty()) {
            return MlPredictResponse.fail("no_features");
        }

        if (request.getFeatureOrder() == null || request.getFeatureOrder().isEmpty()) {
            return MlPredictResponse.fail("feature_order_missing");
        }

        for (String f : request.getFeatureOrder()) {
            if (f == null || f.isBlank()) {
                return MlPredictResponse.fail("feature_order_invalid");
            }
            if (!features.containsKey(f)) {
                return MlPredictResponse.fail("feature_order_missing_feature:" + f);
            }
        }

        if (request.getSchemaHash() == null || request.getSchemaHash().isBlank()) {
            return MlPredictResponse.fail("schema_hash_missing");
        }

        return null;
    }

    private String buildPredictContext(MlPredictRequest req) {
        if (req == null) return "predict";

        int featuresCount = (req.getFeatures() != null ? req.getFeatures().size() : 0);
        int orderCount = (req.getFeatureOrder() != null ? req.getFeatureOrder().size() : 0);

        return "type=" + safe(req.getStrategyType())
               + " chatId=" + req.getChatId()
               + " symbol=" + safe(req.getSymbol())
               + " tf=" + safe(req.getTimeframe())
               + " modelKey=" + safe(req.getModelKey())
               + " schemaHash=" + safe(req.getSchemaHash())
               + " features=" + featuresCount
               + " order=" + orderCount;
    }

    @SuppressWarnings("unchecked")
    private <T> T errorResponse(Class<T> responseType, String error) {
        long now = System.currentTimeMillis();

        if (responseType == MlHealthResponse.class) {
            MlHealthResponse r = new MlHealthResponse();
            r.setOk(false);
            r.setTs(now);
            r.setError(error);
            r.setVersion(null);
            return (T) r;
        }

        if (responseType == MlPredictResponse.class) {
            MlPredictResponse r = MlPredictResponse.fail(error);
            r.setTsMs(now);
            return (T) r;
        }

        if (responseType == MlTrainResponse.class) {
            try {
                MlTrainResponse r = MlTrainResponse.fail(error);
                return (T) r;
            } catch (Throwable ignore) {
                return (T) new MlTrainResponse();
            }
        }

        throw new IllegalStateException("Cannot create error response for " + responseType.getName());
    }

    private String safe(String s) {
        if (s == null) return "null";
        String t = s.replace("\n", " ").replace("\r", " ").trim();
        if (t.isEmpty()) return "null";
        return t.length() > 400 ? t.substring(0, 400) + "..." : t;
    }

    private static String safeMsg(Throwable e) {
        if (e == null) return "null";
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        return m;
    }

    public static OkHttpClient defaultHttp(long connectMs, long readMs) {
        return defaultHttp(connectMs, readMs, readMs);
    }

    public static OkHttpClient defaultHttp(long connectMs, long readMs, long writeMs) {
        long c = Math.max(1, connectMs);
        long r = Math.max(1, readMs);
        long w = Math.max(1, writeMs);

        return new OkHttpClient.Builder()
                .connectTimeout(c, TimeUnit.MILLISECONDS)
                .readTimeout(r, TimeUnit.MILLISECONDS)
                .writeTimeout(w, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
