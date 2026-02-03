package com.chicu.aitradebot.ai.ml;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HttpMlSignalService implements MlSignalService {

    private final MlClient mlClient;

    private volatile Boolean cachedAvailable = null;
    private volatile Instant lastHealthCheckAt = null;

    private static final Duration HEALTH_CACHE_TTL = Duration.ofSeconds(5);

    @Override
    public boolean isAvailable() {
        Instant now = Instant.now();

        if (lastHealthCheckAt != null &&
            Duration.between(lastHealthCheckAt, now).compareTo(HEALTH_CACHE_TTL) < 0) {
            return Boolean.TRUE.equals(cachedAvailable);
        }

        lastHealthCheckAt = now;

        try {
            mlClient.health(); // если упадёт — будет false
            cachedAvailable = true;
            return true;
        } catch (Exception e) {
            cachedAvailable = false;
            return false;
        }
    }

    @Override
    public MlPrediction predict(Long chatId, String symbol, String timeframe, String modelKey, MlFeatures features) {

        Map<String, Object> payload = new HashMap<>();
        payload.put("chatId", chatId);
        payload.put("symbol", symbol);
        payload.put("timeframe", timeframe);

        // modelKey может быть null/"" — это нормально, python может выбрать default
        if (modelKey != null && !modelKey.trim().isEmpty()) {
            payload.put("modelKey", modelKey.trim());
        }

        payload.put("features", features);

        JsonNode out = mlClient.post("/predict", payload);

        // ожидаемый формат:
        // { "probBuy": 0.73, "probSell": 0.27, "modelVersion": "xgb-v1", ... }
        double pBuy = out.path("probBuy").asDouble(Double.NaN);
        double pSell = out.path("probSell").asDouble(Double.NaN);
        String ver = out.path("modelVersion").asText("");

        if (!Double.isFinite(pBuy) || !Double.isFinite(pSell)) {
            throw new IllegalStateException("ML /predict returned invalid probs: " + out);
        }

        // ✅ FIX: MlPrediction требует 4 аргумента (включая raw JsonNode)
        return new MlPrediction(pBuy, pSell, ver, out);
    }
}
