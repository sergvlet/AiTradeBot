package com.chicu.aitradebot.ai.ml.dataset;

import com.chicu.aitradebot.ai.ml.features.FeatureExtractor;

public interface DatasetCollector {

    void onTick(double price, FeatureExtractor extractor);

    int size();
}
