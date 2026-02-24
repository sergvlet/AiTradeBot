package com.chicu.aitradebot.ai.ml.features;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DefaultMlFeatureBuilder implements MlFeatureBuilder {

    @Override
    public Map<String, Object> build(MlFeatureContext ctx) {
        LinkedHashMap<String, Object> f = new LinkedHashMap<>();

        // --- meta (в features, чтобы FastAPI модель принимала строго {"features":{...}}) ---
        f.put("chat_id", ctx.getChatId());
        f.put("strategy", ctx.getStrategyType() != null ? ctx.getStrategyType().name() : null);
        f.put("symbol", safeUpper(ctx.getSymbol()));
        f.put("timeframe", ctx.getTimeframe());
        f.put("action", ctx.getAction());

        List<MlCandle> c = ctx.getCandles();
        if (c == null || c.isEmpty()) {
            mergeExtra(ctx, f);
            return f;
        }

        MlCandle last = c.get(c.size() - 1);
        f.put("ts", last.getTs());
        f.put("open", last.getOpen());
        f.put("high", last.getHigh());
        f.put("low", last.getLow());
        f.put("close", last.getClose());
        f.put("volume", last.getVolume());

        // --- простые фичи свечи ---
        double close = nz(last.getClose());
        double open = nz(last.getOpen());
        double high = nz(last.getHigh());
        double low  = nz(last.getLow());

        double range = high - low;
        double body  = Math.abs(close - open);

        f.put("range_pct", pct(range, close));
        f.put("body_pct", pct(body, close));
        f.put("wick_up_pct", pct(high - Math.max(open, close), close));
        f.put("wick_dn_pct", pct(Math.min(open, close) - low, close));

        // --- returns ---
        f.put("ret_1", ret(c, 1));
        f.put("ret_3", ret(c, 3));
        f.put("ret_5", ret(c, 5));
        f.put("ret_10", ret(c, 10));

        // --- volume stats ---
        double volAvg20 = avgVolume(c, 20);
        f.put("vol_avg_20", volAvg20);
        f.put("vol_ratio_20", volAvg20 > 0 ? nz(last.getVolume()) / volAvg20 : 0.0);

        // --- RSI / EMA / ATR ---
        f.put("rsi_14", rsi(c, 14));
        f.put("ema_12", emaClose(c, 12));
        f.put("ema_26", emaClose(c, 26));
        f.put("ema_diff_12_26", (double) f.get("ema_12") - (double) f.get("ema_26"));
        f.put("atr_14_pct", pct(atr(c, 14), close));

        mergeExtra(ctx, f);
        return f;
    }

    private static void mergeExtra(MlFeatureContext ctx, LinkedHashMap<String, Object> f) {
        Map<String, Object> extra = ctx.getExtra();
        if (extra != null && !extra.isEmpty()) {
            extra.forEach((k, v) -> {
                if (k != null && !k.isBlank() && !f.containsKey(k)) {
                    f.put(k, v);
                }
            });
        }
    }

    private static String safeUpper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static double nz(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return v;
    }

    private static double pct(double x, double base) {
        double b = Math.abs(base);
        if (b < 1e-12) return 0.0;
        return nz(x) / b;
    }

    private static double ret(List<MlCandle> c, int lag) {
        int n = c.size();
        if (n <= lag) return 0.0;
        double now = nz(c.get(n - 1).getClose());
        double prev = nz(c.get(n - 1 - lag).getClose());
        if (Math.abs(prev) < 1e-12) return 0.0;
        return (now - prev) / prev;
    }

    private static double avgVolume(List<MlCandle> c, int win) {
        int n = c.size();
        int from = Math.max(0, n - win);
        double sum = 0.0;
        int cnt = 0;
        for (int i = from; i < n; i++) {
            sum += nz(c.get(i).getVolume());
            cnt++;
        }
        return cnt == 0 ? 0.0 : sum / cnt;
    }

    private static double emaClose(List<MlCandle> c, int period) {
        int n = c.size();
        if (n == 0) return 0.0;

        double k = 2.0 / (period + 1.0);
        double ema = nz(c.get(0).getClose());

        for (int i = 1; i < n; i++) {
            double close = nz(c.get(i).getClose());
            ema = close * k + ema * (1.0 - k);
        }
        return ema;
    }

    private static double rsi(List<MlCandle> c, int period) {
        int n = c.size();
        if (n <= period) return 50.0;

        double gain = 0.0;
        double loss = 0.0;

        // берём последние period изменений
        for (int i = n - period; i < n; i++) {
            double prev = nz(c.get(i - 1).getClose());
            double cur  = nz(c.get(i).getClose());
            double d = cur - prev;
            if (d >= 0) gain += d;
            else loss += -d;
        }

        if (gain < 1e-12 && loss < 1e-12) return 50.0;
        if (loss < 1e-12) return 100.0;

        double rs = gain / loss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private static double atr(List<MlCandle> c, int period) {
        int n = c.size();
        if (n < 2) return 0.0;

        int start = Math.max(1, n - period);
        double sum = 0.0;
        int cnt = 0;

        for (int i = start; i < n; i++) {
            MlCandle cur = c.get(i);
            MlCandle prev = c.get(i - 1);

            double high = nz(cur.getHigh());
            double low  = nz(cur.getLow());
            double prevClose = nz(prev.getClose());

            double tr1 = high - low;
            double tr2 = Math.abs(high - prevClose);
            double tr3 = Math.abs(low - prevClose);

            double tr = Math.max(tr1, Math.max(tr2, tr3));
            sum += tr;
            cnt++;
        }

        return cnt == 0 ? 0.0 : sum / cnt;
    }
}
