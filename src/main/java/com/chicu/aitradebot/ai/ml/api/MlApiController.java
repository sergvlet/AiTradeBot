package com.chicu.aitradebot.ai.ml.api;

import com.chicu.aitradebot.ai.ml.MlClient;
import com.chicu.aitradebot.ai.ml.dto.MlHealthResponse;
import com.chicu.aitradebot.ai.ml.dto.MlPredictRequest;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
import com.chicu.aitradebot.ai.ml.dto.MlTrainRequest;
import com.chicu.aitradebot.ai.ml.dto.MlTrainResponse;
import com.chicu.aitradebot.ai.ml.training.MlTrainingResult;
import com.chicu.aitradebot.ai.ml.training.MlTrainingService;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@RestController
@RequestMapping("/api/ml")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ml", name = "enabled", havingValue = "true")
@ConditionalOnBean(MlClient.class)
public class MlApiController {

    private final MlClient gateway;
    private final MlTrainingService trainingService;

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

    /**
     * ✅ Явный запуск обучения:
     * POST /api/ml/train?chatId=123&type=WINDOW_SCALPING&reason=manual
     * Body не нужен.
     */
    @PostMapping(value = "/train", params = {"chatId", "type"})
    public ResponseEntity<MlTrainingResult> trainNow(@RequestParam("chatId") long chatId,
                                                     @RequestParam("type") String type,
                                                     @RequestParam(value = "reason", required = false) String reason) {
        StrategyType st;
        try {
            String t = (type == null ? "" : type.trim().toUpperCase(Locale.ROOT));
            st = t.isEmpty() ? null : StrategyType.valueOf(t);
        } catch (Exception e) {
            return ResponseEntity.ok(new MlTrainingResult(false, false, null, null, null, "bad_type"));
        }

        try {
            MlTrainingResult r = trainingService.trainNow(chatId, st, reason);
            return ResponseEntity.ok(r != null ? r : new MlTrainingResult(false, false, null, null, null, "null_result"));
        } catch (Exception e) {
            return ResponseEntity.ok(new MlTrainingResult(false, false, null, null, null, "train_exception"));
        }
    }

    /**
     * Старый прокси в sidecar (/train с JSON body) — оставляем,
     * но чтобы не конфликтовал с trainNow(), он работает только если НЕТ query-параметров chatId/type.
     */
    @PostMapping(value = "/train", params = {"!chatId", "!type"})
    public ResponseEntity<MlTrainResponse> trainSidecar(@RequestBody MlTrainRequest req) {
        try {
            MlTrainResponse r = gateway.train(req);
            return ResponseEntity.ok(r != null ? r : MlTrainResponse.fail("null_response"));
        } catch (Exception e) {
            return ResponseEntity.ok(MlTrainResponse.fail(e.getMessage()));
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
}