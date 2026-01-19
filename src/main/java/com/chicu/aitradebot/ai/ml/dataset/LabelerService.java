package com.chicu.aitradebot.ai.ml.dataset;

import java.util.List;

public interface LabelerService {

    /**
     * 0 = HOLD, 1 = BUY, 2 = SELL
     */
    List<Integer> label(List<Double> prices, int lookahead, double tpPct, double slPct);
}
