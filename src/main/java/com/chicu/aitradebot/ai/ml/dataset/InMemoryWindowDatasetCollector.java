package com.chicu.aitradebot.ai.ml.dataset;

import com.chicu.aitradebot.ai.ml.features.FeatureExtractor;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Собираем:
 * - prices (для labeler)
 * - featureRows (X)
 */
public class InMemoryWindowDatasetCollector implements DatasetCollector {

    private final int maxRows;

    @Getter
    private final List<Double> prices = new ArrayList<>();

    @Getter
    private final List<List<Double>> featureRows = new ArrayList<>();

    public InMemoryWindowDatasetCollector(int maxRows) {
        this.maxRows = Math.max(200, maxRows);
    }

    @Override
    public void onTick(double price, FeatureExtractor extractor) {
        if (price <= 0) return;

        prices.add(price);
        if (prices.size() > maxRows) {
            prices.remove(0);
            if (!featureRows.isEmpty()) featureRows.remove(0);
        }

        if (extractor != null && extractor.ready()) {
            List<Double> row = extractor.lastFeatureVector();
            if (row != null && !row.isEmpty()) {
                featureRows.add(row);
                if (featureRows.size() > maxRows) featureRows.remove(0);
            }
        }
    }

    @Override
    public int size() {
        return Math.min(prices.size(), featureRows.size());
    }
}
