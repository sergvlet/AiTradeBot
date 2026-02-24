package com.chicu.aitradebot.ai.ml.features;

import java.util.Map;

public interface MlFeatureBuilder {
    Map<String, Object> build(MlFeatureContext ctx);
}
