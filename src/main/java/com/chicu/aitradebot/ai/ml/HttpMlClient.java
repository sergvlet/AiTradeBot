package com.chicu.aitradebot.ai.ml;

import com.chicu.aitradebot.ai.ml.dto.MlHealthResponse;
import com.chicu.aitradebot.ai.ml.dto.MlPredictRequest;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
import com.chicu.aitradebot.ai.ml.dto.MlTrainRequest;
import com.chicu.aitradebot.ai.ml.dto.MlTrainResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.*;

import java.io.IOException;

@RequiredArgsConstructor
public class HttpMlClient implements MlClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ObjectMapper om;
    private final MlProperties props;

    @Override
    public MlHealthResponse health() {
        Request.Builder rb = new Request.Builder()
                .url(join(props.getBaseUrl(), "/health"))
                .get();

        addAuth(rb);

        try (Response resp = http.newCall(rb.build()).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";

            if (!resp.isSuccessful()) {
                MlHealthResponse r = new MlHealthResponse();
                r.setOk(false);
                r.setTs(System.currentTimeMillis());
                r.setError("http_" + resp.code());
                return r;
            }

            MlHealthResponse parsed = om.readValue(body, MlHealthResponse.class);
            if (parsed.getTs() == null) parsed.setTs(System.currentTimeMillis());
            return parsed;

        } catch (Exception e) {
            MlHealthResponse r = new MlHealthResponse();
            r.setOk(false);
            r.setTs(System.currentTimeMillis());
            r.setError(e.getMessage());
            return r;
        }
    }

    @Override
    public MlPredictResponse predict(MlPredictRequest request) {
        try {
            String payload = om.writeValueAsString(request);
            RequestBody rb = RequestBody.create(payload, JSON);

            Request.Builder req = new Request.Builder()
                    .url(join(props.getBaseUrl(), "/predict"))
                    .post(rb);

            addAuth(req);

            try (Response resp = http.newCall(req.build()).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";

                if (!resp.isSuccessful()) {
                    MlPredictResponse r = new MlPredictResponse();
                    r.setOk(false);
                    r.setTsMs(System.currentTimeMillis());
                    r.setError("http_" + resp.code());
                    r.setProba(null);
                    r.setModelVersion(null);
                    return r;
                }

                MlPredictResponse parsed = om.readValue(body, MlPredictResponse.class);
                if (parsed.getTsMs() == null) parsed.setTsMs(System.currentTimeMillis());
                return parsed;
            }

        } catch (IOException e) {
            MlPredictResponse r = new MlPredictResponse();
            r.setOk(false);
            r.setTsMs(System.currentTimeMillis());
            r.setError("io:" + e.getMessage());
            r.setProba(null);
            r.setModelVersion(null);
            return r;
        } catch (Exception e) {
            MlPredictResponse r = new MlPredictResponse();
            r.setOk(false);
            r.setTsMs(System.currentTimeMillis());
            r.setError(e.getMessage());
            r.setProba(null);
            r.setModelVersion(null);
            return r;
        }
    }

    // =====================================================
    // ✅ TRAIN (добавлено)
    // =====================================================

    @Override
    public MlTrainResponse train(MlTrainRequest request) {
        try {
            String payload = om.writeValueAsString(request);
            RequestBody rb = RequestBody.create(payload, JSON);

            Request.Builder req = new Request.Builder()
                    .url(join(props.getBaseUrl(), "/train"))
                    .post(rb);

            addAuth(req);

            try (Response resp = http.newCall(req.build()).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";

                if (!resp.isSuccessful()) {
                    return MlTrainResponse.fail("http_" + resp.code());
                }

                MlTrainResponse parsed = om.readValue(body, MlTrainResponse.class);
                if (parsed.getTsMs() == null) parsed.setTsMs(System.currentTimeMillis());
                return parsed;
            }

        } catch (IOException e) {
            return MlTrainResponse.fail("io:" + e.getMessage());
        } catch (Exception e) {
            return MlTrainResponse.fail(e.getMessage());
        }
    }

    private void addAuth(Request.Builder rb) {
        String key = props.getApiKey();
        if (key != null && !key.isBlank()) {
            rb.header("X-API-Key", key.trim());
        }
    }

    private static String join(String base, String path) {
        String b = base == null ? "" : base.trim();
        String p = path == null ? "" : path.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (!p.startsWith("/")) p = "/" + p;
        return b + p;
    }
}
