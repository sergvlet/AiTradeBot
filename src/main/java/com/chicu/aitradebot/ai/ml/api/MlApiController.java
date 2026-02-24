package com.chicu.aitradebot.ai.ml.api;

import com.chicu.aitradebot.ai.ml.MlClient;
import com.chicu.aitradebot.ai.ml.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ml")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ml", name = "enabled", havingValue = "true")
@ConditionalOnBean(MlClient.class)
public class MlApiController {

    private final MlClient gateway;

    @GetMapping("/health")
    public ResponseEntity<MlHealthResponse> health() {
        try {
            MlHealthResponse h = gateway.health();
            return ResponseEntity.ok(h != null ? h : failHealth("null"));
        } catch (Exception e) {
            return ResponseEntity.ok(failHealth(e.getMessage()));
        }
    }

    @PostMapping("/predict")
    public ResponseEntity<MlPredictResponse> predict(@RequestBody MlPredictRequest req) {
        try {
            MlPredictResponse r = gateway.predict(req);
            return ResponseEntity.ok(r != null ? r : failPredict("null_response"));
        } catch (Exception e) {
            return ResponseEntity.ok(failPredict(e.getMessage()));
        }
    }

    private static MlHealthResponse failHealth(String msg) {
        MlHealthResponse r = new MlHealthResponse();
        r.setOk(false);
        r.setTs(System.currentTimeMillis());
        r.setError(msg != null ? msg : "error");
        return r;
    }

    private static MlPredictResponse failPredict(String msg) {
        MlPredictResponse r = new MlPredictResponse();
        r.setOk(false);
        r.setTsMs(System.currentTimeMillis());
        r.setError(msg != null ? msg : "error");
        r.setProba(null);
        r.setModelVersion(null);
        return r;
    }

    @PostMapping("/train")
    public ResponseEntity<MlTrainResponse> train(@RequestBody MlTrainRequest req) {
        try {
            MlTrainResponse r = gateway.train(req);
            return ResponseEntity.ok(r != null ? r : MlTrainResponse.fail("null_response"));
        } catch (Exception e) {
            return ResponseEntity.ok(MlTrainResponse.fail(e.getMessage()));
        }
    }
}