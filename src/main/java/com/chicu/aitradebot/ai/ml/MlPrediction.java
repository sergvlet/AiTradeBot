package com.chicu.aitradebot.ai.ml;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MlPrediction
 * ===========
 * Унифицированный результат предикта:
 * - probBuy / probSell: 0..1
 * - modelVersion: строка/версия модели
 * - raw: полный JSON (полезно для дебага)
 */
public record MlPrediction(
        double probBuy,
        double probSell,
        String modelVersion,
        JsonNode raw
) {}
