package com.chicu.aitradebot.ai.ml.dataset;

public interface MlSampleIngestService {

    MlSampleEntity save(MlSampleEntity sample);

    MlSampleEntity saveAndMaybeTrain(MlSampleEntity sample);
}