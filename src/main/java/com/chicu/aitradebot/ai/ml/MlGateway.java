package com.chicu.aitradebot.ai.ml;

import com.chicu.aitradebot.ai.ml.dto.MlHealthResponse;
import com.chicu.aitradebot.ai.ml.dto.MlPredictRequest;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlGateway {

    private final MlProperties props;
    private final MlClient client;

    public boolean isEnabled() {
        return props.isEnabled();
    }

    public MlHealthResponse health() {
        if (!props.isEnabled()) {
            MlHealthResponse r = new MlHealthResponse();
            r.setOk(false);
            r.setError("ML disabled (ml.enabled=false)");
            return r;
        }
        return client.health();
    }

    public MlPredictResponse predict(Map<String, Object> features) {
        if (!props.isEnabled()) {
            MlPredictResponse r = new MlPredictResponse();
            r.setOk(false);
            r.setError("ML disabled (ml.enabled=false)");
            return r;
        }
        MlPredictRequest req = new MlPredictRequest();
        req.setFeatures(features);
        return client.predict(req);
    }
}
