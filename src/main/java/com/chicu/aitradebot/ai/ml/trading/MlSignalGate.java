package com.chicu.aitradebot.ai.ml.trading;

import com.chicu.aitradebot.ai.ml.MlClient;
import com.chicu.aitradebot.ai.ml.MlProperties;
import com.chicu.aitradebot.ai.ml.dto.MlPredictRequest;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
import com.chicu.aitradebot.ai.ml.features.MlFeatureBuilder;
import com.chicu.aitradebot.ai.ml.features.MlFeatureContext;
import com.chicu.aitradebot.ai.ml.policy.StrategyMlPolicyProperties;
import com.chicu.aitradebot.ai.ml.policy.StrategyMlResolved;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.core.signal.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlSignalGate {

    private final MlProperties mlProps;
    private final StrategyMlPolicyProperties policyProps;
    private final MlClient mlClient;
    private final MlFeatureBuilder featureBuilder;

    public Signal gate(Signal raw, MlFeatureContext ctx) {
        if (raw == null) return Signal.hold("raw_null");
        if (raw.getType() == SignalType.HOLD) return raw;
        if (ctx == null) return Signal.hold("ctx_null");

        // глобально выключено
        if (!mlProps.isEnabled()) return raw;

        StrategyMlResolved pol = policyProps.resolve(ctx.getStrategyType());
        if (!pol.enabled()) return raw;

        try {
            Map<String, Object> features = featureBuilder.build(ctx);

            MlPredictRequest req = new MlPredictRequest();
            req.setChatId(ctx.getChatId());
            req.setStrategyType(ctx.getStrategyType() != null ? ctx.getStrategyType().name() : null);
            req.setSymbol(ctx.getSymbol());
            req.setTimeframe(ctx.getTimeframe());
            req.setModelKey(ctx.getModelKey());
            req.setSchemaHash(ctx.getSchemaHash());
            req.setFeatures(features);
            req.setTsMs(Instant.now().toEpochMilli());

            MlPredictResponse resp = mlClient.predict(req);

            if (resp == null || !resp.isOk() || resp.getProba() == null) {
                return pol.failOpen() ? raw : Signal.hold("ml_bad_response");
            }

            double proba = resp.getProba();
            if (!Double.isFinite(proba)) {
                return pol.failOpen() ? raw : Signal.hold("ml_nan");
            }

            if (proba >= pol.minProba()) {
                return raw;
            }

            return Signal.hold("ml_reject p=" + String.format(Locale.US, "%.4f", proba));

        } catch (Exception e) {
            if (pol.failOpen()) {
                log.debug("ML gate fail-open: type={} symbol={} err={}",
                        ctx.getStrategyType(), ctx.getSymbol(), e.toString());
                return raw;
            }
            log.debug("ML gate fail-closed: type={} symbol={} err={}",
                    ctx.getStrategyType(), ctx.getSymbol(), e.toString());
            return Signal.hold("ml_exception");
        }
    }
}
