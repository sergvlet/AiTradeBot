package com.chicu.aitradebot.ai.ml.training;

public record MlTrainingResult(
        boolean ok,
        boolean applied,
        String modelKey,
        String modelVersion,
        String schemaHash,
        String error
) {}
