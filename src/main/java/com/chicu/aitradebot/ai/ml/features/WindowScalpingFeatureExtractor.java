package com.chicu.aitradebot.ai.ml.features;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Минимальный прод-набор фич под WINDOW_SCALPING:
 * - pct_change_1
 * - pct_change_N
 * - range_pct (high-low)/mid
 * - pos_in_range (price-low)/(high-low)
 * - volatility_pct (std/mean)
 */
public class WindowScalpingFeatureExtractor implements FeatureExtractor {

    private final int window;

    private final Deque<Double> prices = new ArrayDeque<>();
    private List<Double> last = List.of();

    private final FeatureSchema schema = new FeatureSchema(List.of(
            "pct_change_1",
            "pct_change_w",
            "range_pct",
            "pos_in_range",
            "volatility_pct"
    ));

    public WindowScalpingFeatureExtractor(int window) {
        this.window = Math.max(5, window);
    }

    @Override
    public FeatureSchema schema() {
        return schema;
    }

    @Override
    public void onPrice(double price) {
        if (price <= 0) return;

        prices.addLast(price);
        while (prices.size() > window) prices.removeFirst();

        if (ready()) {
            last = compute();
        }
    }

    @Override
    public boolean ready() {
        return prices.size() >= window;
    }

    @Override
    public List<Double> lastFeatureVector() {
        return last;
    }

    private List<Double> compute() {
        List<Double> p = new ArrayList<>(prices);

        double first = p.get(0);
        double prev  = p.get(p.size() - 2);
        double lastP = p.get(p.size() - 1);

        double high = first, low = first, sum = 0;
        for (double v : p) {
            high = Math.max(high, v);
            low  = Math.min(low, v);
            sum += v;
        }

        double mean = sum / p.size();
        double var = 0;
        for (double v : p) {
            double d = v - mean;
            var += d * d;
        }
        var /= p.size();
        double std = Math.sqrt(var);

        double pct1 = safePct(prev, lastP);
        double pctW = safePct(first, lastP);

        double mid = (high + low) / 2.0;
        double rangePct = mid > 0 ? ((high - low) / mid) * 100.0 : 0.0;

        double denom = (high - low);
        double posInRange = denom > 0 ? ((lastP - low) / denom) : 0.5;

        double volatilityPct = mean > 0 ? (std / mean) * 100.0 : 0.0;

        return List.of(pct1, pctW, rangePct, posInRange, volatilityPct);
    }

    private static double safePct(double from, double to) {
        if (from == 0) return 0;
        return ((to - from) / from) * 100.0;
    }
}
