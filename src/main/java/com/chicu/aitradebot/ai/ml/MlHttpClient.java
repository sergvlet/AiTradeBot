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
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
public class MlHttpClient implements MlClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ObjectMapper om;
    private final String baseUrl;

    /**
     * Опционально. Если задан — клиент сам ставит X-API-KEY.
     */
    private final String apiKey;

    // ✅ Variant A: старый конструктор (3 аргумента)
    public MlHttpClient(OkHttpClient http, ObjectMapper om, String baseUrl) {
        this(http, om, baseUrl, null);
    }

    // ✅ Новый конструктор (4 аргумента)
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
        return execute(req, MlHealthResponse.class, "health");
    }

    @Override
    public MlPredictResponse predict(MlPredictRequest request) {
        String url = baseUrl + "/predict";
        return postJson(url, request, MlPredictResponse.class, "predict");
    }

    @Override
    public MlTrainResponse train(MlTrainRequest request) {
        String url = baseUrl + "/train";
        return postJson(url, request, MlTrainResponse.class, "train");
    }

    private Request.Builder withAuth(Request.Builder b) {
        if (apiKey != null) {
            b.header("X-API-KEY", apiKey);
        }
        b.header("Accept", "application/json");
        return b;
    }

    private <T> T postJson(String url, Object body, Class<T> responseType, String op) {
        try {
            String json = om.writeValueAsString(body);
            RequestBody rb = RequestBody.create(json, JSON);

            Request req = withAuth(new Request.Builder().url(url).post(rb)).build();
            return execute(req, responseType, op);

        } catch (Exception e) {
            log.warn("🧠 ML {}: failed to serialize request: {}", op, e.toString());
            return errorResponse(responseType, "serialize_error: " + safeMsg(e));
        }
    }

    private <T> T execute(Request req, Class<T> responseType, String op) {
        long ts = System.currentTimeMillis();
        try (Response resp = http.newCall(req).execute()) {

            long tookMs = System.currentTimeMillis() - ts;
            String body = resp.body() != null ? resp.body().string() : null;

            if (!resp.isSuccessful()) {
                log.warn("🧠 ML {}: http={} tookMs={} body={}", op, resp.code(), tookMs, safe(body));
                return errorResponse(responseType, "http_" + resp.code());
            }

            if (body == null || body.isBlank()) {
                log.warn("🧠 ML {}: empty body tookMs={}", op, tookMs);
                return errorResponse(responseType, "empty_body");
            }

            return om.readValue(body, responseType);

        } catch (IOException e) {
            long tookMs = System.currentTimeMillis() - ts;
            log.warn("🧠 ML {}: io error tookMs={} err={}", op, tookMs, e.toString());
            return errorResponse(responseType, "io_error: " + safeMsg(e));
        } catch (Exception e) {
            long tookMs = System.currentTimeMillis() - ts;
            log.warn("🧠 ML {}: parse error tookMs={} err={}", op, tookMs, e.toString());
            return errorResponse(responseType, "parse_error: " + safeMsg(e));
        }
    }

    private String safe(String s) {
        if (s == null) return null;
        String t = s.replace("\n", " ").replace("\r", " ");
        return t.length() > 400 ? t.substring(0, 400) + "..." : t;
    }

    private static String safeMsg(Throwable e) {
        if (e == null) return "null";
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        return m;
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
            // ✅ не возвращаем null — gating должен получить ok=false + error
            return (T) MlPredictResponse.fail(error);
        }

        if (responseType == MlTrainResponse.class) {
            try {
                return (T) MlTrainResponse.fail(error);
            } catch (Throwable ignore) {
                try {
                    T t = responseType.getDeclaredConstructor().newInstance();
                    // если там есть setOk/setError — заполним мягко
                    tryInvokeSetters(t, now, error);
                    return t;
                } catch (Exception e) {
                    return (T) new MlTrainResponse();
                }
            }
        }

        try {
            T t = responseType.getDeclaredConstructor().newInstance();
            tryInvokeSetters(t, now, error);
            return t;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create error response for " + responseType.getName());
        }
    }

    private void tryInvokeSetters(Object t, long now, String error) {
        if (t == null) return;
        try {
            var m = t.getClass().getMethod("setOk", boolean.class);
            m.invoke(t, false);
        } catch (Exception ignored) {}
        try {
            var m = t.getClass().getMethod("setError", String.class);
            m.invoke(t, error);
        } catch (Exception ignored) {}
        try {
            var m = t.getClass().getMethod("setTsMs", Long.class);
            m.invoke(t, now);
        } catch (Exception ignored) {}
        try {
            var m = t.getClass().getMethod("setTs", Long.class);
            m.invoke(t, now);
        } catch (Exception ignored) {}
    }

    /** ✅ совместимость: если кто-то зовёт defaultHttp(connect, read) */
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