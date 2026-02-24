package com.chicu.aitradebot.ai.ml;

import com.chicu.aitradebot.ai.ml.dto.*;

public interface MlClient {

    MlHealthResponse health();

    MlPredictResponse predict(MlPredictRequest request);

    MlTrainResponse train(MlTrainRequest req);


}
