package com.chicu.aitradebot.ai.ml.dataset;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SimpleTpSlLabelerService implements LabelerService {

    @Override
    public List<Integer> label(List<Double> prices, int lookahead, double tpPct, double slPct) {
        int n = prices.size();
        List<Integer> y = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            if (i + lookahead >= n) {
                y.add(0); // хвост не размечаем агрессивно
                continue;
            }

            double entry = prices.get(i);
            if (entry <= 0) {
                y.add(0);
                continue;
            }

            double tp = entry * (1.0 + tpPct / 100.0);
            double sl = entry * (1.0 - slPct / 100.0);

            int label = 0;
            for (int j = i + 1; j <= i + lookahead; j++) {
                double future = prices.get(j);
                if (future >= tp) { label = 1; break; }
                if (future <= sl) { label = 2; break; }
            }
            y.add(label);
        }
        return y;
    }
}
