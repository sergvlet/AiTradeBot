package com.chicu.aitradebot.ai.ml.dto;

public record MlPrediction(
        boolean ok,
        double probBuy,
        double probSell,
        String modelVersion,
        String reason
) {
    public static MlPrediction ok(double probBuy, double probSell, String modelVersion) {
        return new MlPrediction(true, probBuy, probSell, modelVersion, null);
    }

    public static MlPrediction fail(String reason) {
        return new MlPrediction(false, 0.0d, 0.0d, null, reason);
    }
}
