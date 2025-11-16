package com.chicu.aitradebot.web.controller.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/chart")
public class TestChartController {

    @Data
    @AllArgsConstructor
    static class Candle {
        long time;
        double open;
        double high;
        double low;
        double close;
    }

    @Data
    @AllArgsConstructor
    static class EmaPoint {
        long time;
        double value;
    }

    @Data
    @AllArgsConstructor
    static class Trade {
        long id;
        long time;
        String side;
        double price;
        double qty;

        String entryReason;
        String exitReason;

        Double tpPrice;
        Double slPrice;

        Double exitPrice;
        Long exitTime;

        Boolean tpHit;
        Boolean slHit;

        Double pnlUsd;
        Double pnlPct;

        Double mlConfidence;
    }

    @Data
    @AllArgsConstructor
    static class Response {
        List<Candle> candles;
        List<EmaPoint> emaFast;
        List<EmaPoint> emaSlow;
        List<Trade> trades;
    }

    @GetMapping("/test")
    public Response testChart(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "15m") String timeframe
    ) {

        Random rnd = new Random();

        int count = 250;
        List<Candle> candles = new ArrayList<>();

        long now = System.currentTimeMillis();
        long step = 60_000 * 15; // 15m

        double price = 68000;

        // -------------------------
        // 1) Генерация свечей
        // -------------------------
        for (int i = 0; i < count; i++) {
            long t = now - (count - i) * step;

            double open = price + rnd.nextGaussian() * 50;
            double close = open + rnd.nextGaussian() * 40;
            double high = Math.max(open, close) + rnd.nextDouble() * 30;
            double low = Math.min(open, close) - rnd.nextDouble() * 30;

            candles.add(new Candle(t, open, high, low, close));

            price = close;
        }

        // -------------------------
        // 2) EMA fast / slow
        // -------------------------
        List<EmaPoint> emaFast = new ArrayList<>();
        List<EmaPoint> emaSlow = new ArrayList<>();

        double ema9 = candles.get(0).close;
        double ema21 = candles.get(0).close;

        for (Candle c : candles) {
            ema9 = ema9 + (c.close - ema9) * (2.0 / (9 + 1));
            ema21 = ema21 + (c.close - ema21) * (2.0 / (21 + 1));

            emaFast.add(new EmaPoint(c.time, ema9));
            emaSlow.add(new EmaPoint(c.time, ema21));
        }

        // -------------------------
        // 3) Генерация сделок
        // -------------------------
        List<Trade> trades = new ArrayList<>();

        for (int i = 50; i < 200; i += 35) {
            Candle c = candles.get(i);

            String side = (i % 2 == 0) ? "BUY" : "SELL";

            double qty = 0.002;
            double priceEntry = c.close;

            double tp = (side.equals("BUY"))
                    ? priceEntry + 300
                    : priceEntry - 300;

            double sl = (side.equals("BUY"))
                    ? priceEntry - 300
                    : priceEntry + 300;

            boolean hitTp = rnd.nextBoolean();
            boolean hitSl = !hitTp;

            double exitPrice = hitTp ? tp : sl;
            long exitTime = c.time + 60_000 * 20;

            double pnlUsd = (exitPrice - priceEntry) * (side.equals("BUY") ? 1 : -1) * 1;
            double pnlPct = (exitPrice - priceEntry) / priceEntry * 100 * (side.equals("BUY") ? 1 : -1);

            trades.add(new Trade(
                    i,                          // id
                    c.time,                     // time
                    side,                       // BUY/SELL
                    priceEntry,                 // entry
                    qty,                        // qty
                    side.equals("BUY") ? "EMA Cross + RSI Oversold" : "EMA Cross + RSI Overbought",
                    hitTp ? "Take Profit" : "Stop Loss",  // exit reason
                    tp,
                    sl,
                    exitPrice,
                    exitTime,
                    hitTp,
                    hitSl,
                    pnlUsd,
                    pnlPct,
                    rnd.nextDouble()            // ml confidence
            ));
        }

        log.info("🔥 TEST chart generated: {} candles, {} trades", candles.size(), trades.size());

        return new Response(candles, emaFast, emaSlow, trades);
    }
}
