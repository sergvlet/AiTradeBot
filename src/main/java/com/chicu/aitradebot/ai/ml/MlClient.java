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
        Request req = new Request.Builder()
                .url(props.getBaseUrl() + "/health")
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

            Request req = new Request.Builder()
                    .url(props.getBaseUrl() + path)
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
}
