package com.chicu.aitradebot.ai.ml;

import com.chicu.aitradebot.ai.ml.dto.MlHealthResponse;
import com.chicu.aitradebot.ai.ml.dto.MlPredictRequest;
import com.chicu.aitradebot.ai.ml.dto.MlPredictResponse;
import com.chicu.aitradebot.ai.ml.dto.MlTrainRequest;
import com.chicu.aitradebot.ai.ml.dto.MlTrainResponse;

public interface MlClient {

    MlHealthResponse health();

    MlPredictResponse predict(MlPredictRequest request);

    MlTrainResponse train(MlTrainRequest req);
}

