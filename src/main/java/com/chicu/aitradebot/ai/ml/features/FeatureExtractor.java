package com.chicu.aitradebot.ai.ml.features;

import java.util.List;

public interface FeatureExtractor {

    FeatureSchema schema();

    /**
     * Обновляем extractor новым тиком/ценой.
     */
    void onPrice(double price);

    /**
     * Готов ли вектор фич (например, набрали окно).
     */
    boolean ready();

    /**
     * Последний рассчитанный вектор (одна строка X).
     */
    List<Double> lastFeatureVector();
}
