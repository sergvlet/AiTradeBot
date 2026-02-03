package com.chicu.aitradebot.ai.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.*;

import java.io.IOException;
import java.util.Objects;

@RequiredArgsConstructor
public class MlClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ObjectMapper om;
    private final MlProperties props;

    public JsonNode health() {
        Request req = baseRequest("/health")
                .get()
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("ML /health HTTP " + resp.code());
            }
            String body = Objects.requireNonNull(resp.body()).string();
            return om.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("ML /health failed: " + e.getMessage(), e);
        }
    }

    public JsonNode post(String path, Object payload) {
        try {
            String json = om.writeValueAsString(payload);
            RequestBody rb = RequestBody.create(json, JSON);

            Request req = baseRequest(path)
                    .post(rb)
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    throw new IllegalStateException("ML " + path + " HTTP " + resp.code() + " body=" + body);
                }
                return om.readTree(body);
            }
        } catch (Exception e) {
            throw new IllegalStateException("ML POST " + path + " failed: " + e.getMessage(), e);
        }
    }

    private Request.Builder baseRequest(String path) {
        String url = join(props.getBaseUrl(), path);

        Request.Builder b = new Request.Builder().url(url);

        String apiKey = props.getApiKey();
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            // если у тебя на python ожидается другое имя заголовка — поменяешь тут в одном месте
            b.header("X-API-KEY", apiKey.trim());
        }

        return b;
    }

    private static String join(String baseUrl, String path) {
        String b = (baseUrl == null ? "" : baseUrl.trim());
        String p = (path == null ? "" : path.trim());

        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (!p.startsWith("/")) p = "/" + p;

        return b + p;
    }
}
