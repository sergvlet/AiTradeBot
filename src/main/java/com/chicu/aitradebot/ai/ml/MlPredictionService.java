package com.chicu.aitradebot.ai.ml;

import com.chicu.aitradebot.ai.ml.features.FeatureSchema;
import com.chicu.aitradebot.ai.ml.sidecar.MlSidecarClient;
import com.chicu.aitradebot.ai.ml.sidecar.dto.PredictRequestDto;
import com.chicu.aitradebot.ai.ml.sidecar.dto.PredictResponseDto;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlPredictionService {

    private final MlSidecarClient client;
    private final ModelKeyFactory keyFactory;

    /**
     * @param featuresMap фичи по именам schema.featureNames()
     */
    public PredictResponseDto predict(
            StrategyType type,
            String symbol,
            String timeframe,
            String exchange,
            NetworkType network,
            FeatureSchema schema,
            Map<String, Double> featuresMap
    ) {
        if (schema == null) return PredictResponseDto.fail("schema=null");

        String modelKey = keyFactory.build(type, symbol, timeframe, exchange, network, schema.schemaHash());
        double[] x = schema.toVector(featuresMap);

        PredictRequestDto req = PredictRequestDto.builder()
                .modelKey(modelKey)
                .featureNames(schema.featureNames())
                .x(x)
                .meta(Map.of(
                        "schemaHash", schema.schemaHash()
                ))
                .build();

        PredictResponseDto resp;
        try {
            resp = client.predict(req);
        } catch (Exception e) {
            log.warn("🧠 PREDICT FAIL modelKey={} err={}", modelKey, e.toString());
            return PredictResponseDto.fail("predict exception: " + e.getMessage());
        }

        if (resp == null) return PredictResponseDto.fail("predict resp=null");

        if (!resp.isOk()) {
            log.warn("🧠 PREDICT not ok modelKey={} msg={}", modelKey, resp.getMessage());
            return resp;
        }

        log.debug("🧠 PREDICT OK modelKey={} label={} prob={} ver={}",
                modelKey, resp.getLabel(), resp.getProb(), resp.getModelVersion());

        return resp;
    }

    /**
     * На случай если где-то у тебя остался старый формат (List<Double>)
     */
    public PredictResponseDto predict(
            StrategyType type,
            String symbol,
            String timeframe,
            String exchange,
            NetworkType network,
            FeatureSchema schema,
            List<Double> xList
    ) {
        if (xList == null) return PredictResponseDto.fail("xList=null");
        double[] x = new double[xList.size()];
        for (int i = 0; i < x.length; i++) {
            Double v = xList.get(i);
            x[i] = (v != null && Double.isFinite(v)) ? v : 0.0;
        }

        String modelKey = keyFactory.build(type, symbol, timeframe, exchange, network, schema != null ? schema.schemaHash() : "no_schema");

        PredictRequestDto req = PredictRequestDto.builder()
                .modelKey(modelKey)
                .featureNames(schema != null ? schema.featureNames() : new String[0])
                .x(x)
                .build();

        try {
            PredictResponseDto resp = client.predict(req);
            return resp != null ? resp : PredictResponseDto.fail("predict resp=null");
        } catch (Exception e) {
            return PredictResponseDto.fail("predict exception: " + e.getMessage());
        }
    }
}
