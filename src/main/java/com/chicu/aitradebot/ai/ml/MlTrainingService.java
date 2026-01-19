package com.chicu.aitradebot.ai.ml;

import com.chicu.aitradebot.ai.ml.dataset.TrainingDatasetBuilder;
import com.chicu.aitradebot.ai.ml.features.FeatureSchema;
import com.chicu.aitradebot.ai.ml.sidecar.MlSidecarClient;
import com.chicu.aitradebot.ai.ml.sidecar.dto.TrainRequestDto;
import com.chicu.aitradebot.ai.ml.sidecar.dto.TrainResponseDto;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlTrainingService {

    private final MlSidecarClient client;
    private final ModelKeyFactory keyFactory;
    private final TrainingDatasetBuilder datasetBuilder;

    public TrainResponseDto train(
            StrategyType type,
            String symbol,
            String timeframe,
            String exchange,
            NetworkType network,
            FeatureSchema schema,
            TrainingDatasetBuilder.Dataset dataset
    ) {
        if (schema == null) throw new IllegalArgumentException("schema=null");
        if (dataset == null) throw new IllegalArgumentException("dataset=null");

        String schemaHash = schema.schemaHash();
        String modelKey = keyFactory.build(type, symbol, timeframe, exchange, network, schemaHash);

        TrainRequestDto req = TrainRequestDto.builder()
                .modelKey(modelKey)
                .featureNames(schema.featureNames())     // ✅ важно: featureNames как в python
                .X(dataset.X())
                .y(dataset.y())
                // schemaHash / datasetId не шлём отдельными полями — python их не ждёт
                .meta(Map.of(
                        "schemaHash", schemaHash,
                        "datasetId", dataset.datasetId()
                ))
                .build();

        TrainResponseDto resp = client.train(req);

        if (resp != null && resp.isOk()) {
            log.info("🧠 TRAIN OK modelKey={} modelPath={} modelVersion={} msg={}",
                    modelKey, resp.getModelPath(), resp.getModelVersion(), resp.getMessage());
        } else {
            log.warn("🧠 TRAIN FAIL modelKey={} resp={}", modelKey, resp);
        }

        return resp;
    }

    public TrainingDatasetBuilder.Dataset buildDataset(TrainingDatasetBuilder.Rows rows) {
        return datasetBuilder.build(rows);
    }
}
