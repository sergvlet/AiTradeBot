package com.chicu.aitradebot.web.service;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.repository.OrderRepository;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.CandleProvider;
import com.chicu.aitradebot.strategy.core.DashboardSupport;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.registry.StrategyRegistry;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettingsService;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;
import com.chicu.aitradebot.web.controller.web.dto.StrategyChartDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 📊 Универсальный сервис формирования данных для дашборда стратегии.
 * Полностью исправлен для поддержки SmartFusion + новой модели Order.
 */
@Service
@RequiredArgsConstructor
public class StrategyDashboardService {

    private final StrategyRegistry registry;
    private final StrategySettingsService settingsService;
    private final SmartFusionStrategySettingsService sfSettingsService;
    private final SmartFusionCandleService smartCandle;
    private final OrderService orderService;
    private final OrderRepository orderRepo;

    public StrategyChartDto build(long chatId, String type, int limit) {
        return build(chatId, type, limit, null);
    }

    public StrategyChartDto build(long chatId, String type, int limit, String tf) {

        TradingStrategy strategy = registry.getStrategy(type);
        if (strategy == null)
            throw new IllegalArgumentException("Стратегия не найдена: " + type);

        StrategyType st = StrategyType.valueOf(type);

        boolean isSmart = st == StrategyType.SMART_FUSION;
        SmartFusionStrategySettings sf = null;

        if (isSmart) {
            sf = (SmartFusionStrategySettings) sfSettingsService.findByChatId(chatId)
                    .orElseThrow(() -> new IllegalStateException(
                            "SmartFusion настройки не найдены: chatId=" + chatId));
        }

        // ------------------ SYMBOL ------------------
        String symbol =
                isSmart ? sf.getSymbol() :
                        Optional.ofNullable(settingsService.getSettings(chatId, st))
                                .map(StrategySettings::getSymbol)
                                .orElse("BTCUSDT");

        // ------------------ TIMEFRAME ------------------
        String timeframe;

        if (isSmart) {
            timeframe = (tf != null && !tf.isBlank()) ? tf : sf.getTimeframe();
        } else {
            timeframe = sanitizeTimeframe(tf);
            if (timeframe == null) timeframe = "15m";
        }

        // ------------------ CANDLES ------------------
        CandleProvider provider =
                (strategy instanceof DashboardSupport ds)
                        ? ds.getCandleProvider().orElse(this::fallbackCandles)
                        : this::fallbackCandles;

        List<CandleProvider.Candle> raw =
                provider.getRecentCandles(chatId, symbol, timeframe, limit);

        var candles = raw.stream()
                .map(c -> StrategyChartDto.CandleDto.builder()
                        .time(c.ts().toEpochMilli())
                        .open(c.open())
                        .high(c.high())
                        .low(c.low())
                        .close(c.close())
                        .volume(0)
                        .build())
                .toList();

        // ------------------ EMA ------------------
        int fast = isSmart ? sf.getEmaFastPeriod() : 9;
        int slow = isSmart ? sf.getEmaSlowPeriod() : 21;

        var emaFast = ema(raw, fast);
        var emaSlow = ema(raw, slow);

        // ------------------ TRADES ------------------
        var orders = orderService.getOrdersByChatIdAndSymbol(chatId, symbol);

        var trades = orders.stream()
                .map(o -> StrategyChartDto.TradeMarker.builder()
                        .time(o.getTime() != null ? o.getTime() : System.currentTimeMillis())
                        .side(o.getSide())
                        .price(toD(o.getPrice()))
                        .qty(toD(o.getQuantity()))
                        .build())
                .toList();

        // ------------------ TP/SL ------------------
        double last = candles.isEmpty() ? 0 : candles.get(candles.size() - 1).getClose();

        double tpPct = isSmart
                ? sf.getTakeProfitAtrMult() * 10
                : Optional.ofNullable(settingsService.getSettings(chatId, st))
                .map(StrategySettings::getTakeProfitPct)
                .map(BigDecimal::doubleValue)
                .orElse(1.0);

        double slPct = isSmart
                ? sf.getStopLossAtrMult() * 10
                : Optional.ofNullable(settingsService.getSettings(chatId, st))
                .map(StrategySettings::getStopLossPct)
                .map(BigDecimal::doubleValue)
                .orElse(1.0);

        List<Double> tp = last > 0 ? List.of(last * (1 + tpPct / 100.0)) : List.of();
        List<Double> sl = last > 0 ? List.of(last * (1 - slPct / 100.0)) : List.of();

        // ------------------ EQUITY + KPI + MONTHLY ------------------
        var equity = equitySeries(candles, trades);
        var kpis = computeKpis(chatId, symbol);
        var monthly = computeMonthlyPnl(chatId, symbol);

        return StrategyChartDto.builder()
                .candles(candles)
                .emaFast(mapLine(emaFast))
                .emaSlow(mapLine(emaSlow))
                .trades(trades)
                .tpLevels(tp)
                .slLevels(sl)
                .equity(mapLine(equity))
                .kpis(kpis)
                .monthlyPnl(monthly)
                .build();
    }

    // ------------------ TIMEFRAMES ------------------

    private static final Set<String> ALLOWED_TF = Set.of(
            "1s","5s","10s","15s","30s",
            "1m","3m","5m","15m","30m",
            "1h","2h","4h","6h","12h",
            "1d","3d","1w","1M"
    );

    private String sanitizeTimeframe(String tf) {
        if (tf == null) return null;
        return ALLOWED_TF.contains(tf.trim()) ? tf : null;
    }

    // ------------------ EMA ------------------
    private List<Point> ema(List<CandleProvider.Candle> candles, int period) {
        if (period <= 1 || candles.isEmpty()) return List.of();
        double k = 2.0 / (period + 1);
        List<Point> out = new ArrayList<>();
        double prev = candles.get(0).close();
        out.add(new Point(candles.get(0).ts().toEpochMilli(), prev));

        for (int i = 1; i < candles.size(); i++) {
            double v = candles.get(i).close() * k + prev * (1 - k);
            out.add(new Point(candles.get(i).ts().toEpochMilli(), v));
            prev = v;
        }
        return out;
    }

    // ------------------ EQUITY ------------------
    private List<Point> equitySeries(List<StrategyChartDto.CandleDto> candles,
                                     List<StrategyChartDto.TradeMarker> trades) {
        double equity = 100.0;

        Map<Long, List<StrategyChartDto.TradeMarker>> grouped = new HashMap<>();
        for (var t : trades)
            grouped.computeIfAbsent(t.getTime(), k -> new ArrayList<>()).add(t);

        List<Point> out = new ArrayList<>();
        for (var c : candles) {
            long ts = c.getTime();
            double price = c.getClose();

            if (grouped.containsKey(ts)) {
                for (var tr : grouped.get(ts)) {
                    double qty = tr.getQty();
                    double delta = (tr.getSide().equalsIgnoreCase("BUY") ? 1 : -1)
                                   * price * qty * 0.0005;
                    equity += delta;
                }
            }
            out.add(new Point(ts, equity));
        }
        return out;
    }

    // ------------------ KPI ------------------
    private Map<String, Double> computeKpis(long chatId, String symbol) {
        var orders = orderRepo.findByChatIdAndSymbolOrderByTimestampAsc(chatId, symbol);

        int wins = 0, losses = 0, cw = 0, cl = 0, maxW = 0, maxL = 0;
        double grossProfit = 0, grossLoss = 0, net = 0;

        for (var e : orders) {
            double pnl = pnlOf(e);
            net += pnl;
            if (pnl > 0) {
                wins++; cw++; cl = 0; grossProfit += pnl; maxW = Math.max(maxW, cw);
            } else if (pnl < 0) {
                losses++; cl++; cw = 0; grossLoss += Math.abs(pnl); maxL = Math.max(maxL, cl);
            }
        }

        Map<String, Double> kpi = new LinkedHashMap<>();
        kpi.put("netProfit", r2(net));
        kpi.put("winRate", r2(orders.isEmpty() ? 0 : wins * 100.0 / orders.size()));
        kpi.put("profitFactor", r2(grossLoss == 0 ? (grossProfit > 0 ? 999 : 0) : grossProfit / grossLoss));
        kpi.put("totalTrades", (double) orders.size());
        kpi.put("maxWins", (double) maxW);
        kpi.put("maxLosses", (double) maxL);
        return kpi;
    }

    // ------------------ MONTHLY PNL ------------------
    private Map<String, Double> computeMonthlyPnl(long chatId, String symbol) {

        DateTimeFormatter fmt =
                DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

        var orders = orderRepo.findByChatIdAndSymbolOrderByTimestampAsc(chatId, symbol);
        Map<String, Double> out = new LinkedHashMap<>();

        for (var e : orders) {
            if (e.getCreatedAt() == null) continue;
            String ym = fmt.format(e.getCreatedAt().atZone(ZoneOffset.UTC));
            out.merge(ym, pnlOf(e), Double::sum);
        }
        return out;
    }

    // ------------------ FALLBACK ------------------
    private List<CandleProvider.Candle> fallbackCandles(
            long chatId, String symbol, String timeframe, int limit) {

        return smartCandle.getCandles(
                        smartCandle.buildSettings(chatId, symbol, timeframe, limit))
                .stream()
                .map(c -> new CandleProvider.Candle(
                        c.ts(), c.open(), c.high(), c.low(), c.close(), 0))
                .toList();
    }

    // ------------------ UTIL ------------------

    private record Point(long time, double value) {}

    private List<StrategyChartDto.LinePoint> mapLine(List<Point> pts) {
        return pts.stream()
                .map(p -> StrategyChartDto.LinePoint.builder()
                        .time(p.time())
                        .value(p.value())
                        .build())
                .toList();
    }

    private static double toD(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    private static double pnlOf(com.chicu.aitradebot.domain.OrderEntity e) {
        if (e.getTotal() == null || e.getPrice() == null || e.getQuantity() == null)
            return 0.0;
        return e.getTotal().subtract(e.getPrice().multiply(e.getQuantity())).doubleValue();
    }

    private static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
