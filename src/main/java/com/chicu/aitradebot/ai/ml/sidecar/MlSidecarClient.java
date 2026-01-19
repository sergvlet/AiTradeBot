package com.chicu.aitradebot.ai.ml.sidecar;

import com.chicu.aitradebot.ai.ml.sidecar.dto.PredictRequestDto;
import com.chicu.aitradebot.ai.ml.sidecar.dto.PredictResponseDto;
import com.chicu.aitradebot.ai.ml.sidecar.dto.TrainRequestDto;
import com.chicu.aitradebot.ai.ml.sidecar.dto.TrainResponseDto;
import com.chicu.aitradebot.ai.ml.sidecar.props.MlSidecarProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class MlSidecarClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient baseClient;
    private final ObjectMapper objectMapper;
    private final MlSidecarProperties props;

    private OkHttpClient clientWithTimeouts() {
        return baseClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(200, props.getConnectTimeoutMs())))
                .readTimeout(Duration.ofMillis(Math.max(500, props.getReadTimeoutMs())))
                .build();
    }

    public TrainResponseDto train(TrainRequestDto req) {
        return post("/train", req, TrainResponseDto.class);
    }

    public PredictResponseDto predict(PredictRequestDto req) {
        return post("/predict", req, PredictResponseDto.class);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        String url = props.getBaseUrl().replaceAll("/+$", "") + path;

        try {
            String json = objectMapper.writeValueAsString(body);

            Request.Builder rb = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(json, JSON));

            if (props.getApiKey() != null && !props.getApiKey().isBlank()) {
                rb.header("X-API-KEY", props.getApiKey().trim());
            }

            try (Response resp = clientWithTimeouts().newCall(rb.build()).execute()) {

                String respBody = resp.body() != null ? resp.body().string() : "";

                if (!resp.isSuccessful()) {
                    log.warn("🧠 ML sidecar error: {} {} -> {} body={}", "POST", path, resp.code(), shrink(respBody));
                    throw new IllegalStateException("ML sidecar HTTP " + resp.code() + ": " + shrink(respBody));
                }

                if (respBody == null || respBody.isBlank()) {
                    throw new IllegalStateException("ML sidecar пустой ответ: " + path);
                }

                return objectMapper.readValue(respBody, responseType);
            }

        } catch (IOException e) {
            throw new IllegalStateException("ML sidecar IO error: " + url + " -> " + e.getMessage(), e);
        }
    }

    private static String shrink(String s) {
        if (s == null) return "null";
        String x = s.trim().replaceAll("\\s+", " ");
        if (x.length() <= 400) return x;
        return x.substring(0, 400) + "...";
    }
}
