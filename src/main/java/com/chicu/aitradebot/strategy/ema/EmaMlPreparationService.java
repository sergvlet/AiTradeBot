package com.chicu.aitradebot.strategy.ema;

import com.chicu.aitradebot.ai.ml.MlClient;
import com.chicu.aitradebot.ai.ml.MlGateway;
import com.chicu.aitradebot.ai.ml.MlTrainProperties;
import com.chicu.aitradebot.ai.ml.dto.MlTrainRequest;
import com.chicu.aitradebot.ai.ml.dto.MlTrainResponse;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmaMlPreparationService {

    public static final List<String> FEATURE_SCHEMA = List.of(
            "bullRegime",
            "bullishConfirmBars",
            "confirmBars",
            "crossDown",
            "crossUp",
            "emaFast",
            "emaSlow",
            "fast",
            "fastSlopePct",
            "maxSpreadPct",
            "price",
            "priceVsFastPct",
            "priceVsSlowPct",
            "ret1Pct",
            "ret3Pct",
            "ret5Pct",
            "slow",
            "slowSlopePct",
            "spreadPct",
            "volatilityPct"
    );

    private static final BigDecimal DEFAULT_TP_PCT = new BigDecimal("1.20");
    private static final BigDecimal DEFAULT_SL_PCT = new BigDecimal("0.80");
    private static final int DEFAULT_LIMIT = 1200;
    private static final int MAX_LIMIT = 5000;
    private static final int MIN_LIMIT = 200;

    private final ObjectProvider<MlClient> mlClientProvider;
    private final StrategySettingsService strategySettingsService;
    private final EmaCrossoverStrategySettingsService emaSettingsService;
    private final ObjectProvider<MlTrainProperties> trainPropertiesProvider;
    private final ApplicationContext applicationContext;

    public record PrepareResult(boolean ok,
                                boolean applied,
                                int rows,
                                String modelKey,
                                String modelVersion,
                                String schemaHash,
                                String reason) {
    }

    public record CandlePoint(double open,
                              double high,
                              double low,
                              double close,
                              long openTimeMs,
                              long closeTimeMs) {
    }

    private record TrainingStats(int positives,
                                 int negatives,
                                 BigDecimal positiveRate) {
    }

    public PrepareResult prepare(Long chatId,
                                 StrategySettings ss,
                                 EmaCrossoverStrategySettings cfg,
                                 String reason) {

        if (chatId == null || chatId <= 0) {
            return new PrepareResult(false, false, 0, null, null, null, "bad_chatId");
        }
        if (ss == null) {
            return new PrepareResult(false, false, 0, null, null, null, "settings_null");
        }

        MlClient client = mlClientProvider != null ? mlClientProvider.getIfAvailable() : null;
        if (client == null) {
            return new PrepareResult(false, false, 0, null, null, null, "ml_client_missing");
        }

        String exchange = normUpper(ss.getExchangeName());
        NetworkType network = ss.getNetworkType();
        String networkName = network != null ? network.name() : null;
        String symbol = normUpper(ss.getSymbol());
        String timeframe = normLower(ss.getTimeframe());

        if (exchange == null || networkName == null || symbol == null || timeframe == null) {
            return new PrepareResult(false, false, 0, null, null, null, "context_missing");
        }

        EmaCrossoverStrategySettings effectiveCfg = cfg != null ? cfg : emaSettingsService.getOrCreate(chatId);
        int candlesLimit = clampInt(
                ss.getCachedCandlesLimit() != null ? ss.getCachedCandlesLimit() : DEFAULT_LIMIT,
                MIN_LIMIT,
                MAX_LIMIT
        );

        List<CandlePoint> points = loadCandlePoints(chatId, ss, candlesLimit);
        if (points.isEmpty()) {
            return new PrepareResult(false, false, 0, null, null, null, "no_selected_candles");
        }

        BigDecimal tpPct = sanitizePct(effectiveCfg.getTakeProfitPct(), DEFAULT_TP_PCT);
        BigDecimal slPct = sanitizePct(effectiveCfg.getStopLossPct(), DEFAULT_SL_PCT);

        List<Map<String, Object>> rows = buildRows(chatId, symbol, exchange, networkName, timeframe, effectiveCfg, points, tpPct, slPct);
        TrainingStats trainingStats = summarizeRows(rows);
        int minSamples = resolveMinSamples();
        if (rows.size() < minSamples) {
            return new PrepareResult(false, false, rows.size(), null, null, computeSchemaHash(), "not_enough_candle_rows=" + rows.size());
        }

        String modelKey = MlGateway.buildContextModelKey(
                StrategyType.EMA_CROSSOVER,
                exchange,
                networkName,
                symbol,
                timeframe
        );
        String schemaHash = computeSchemaHash();

        MlTrainRequest req = new MlTrainRequest();
        req.setChatId(chatId);
        req.setStrategyType(StrategyType.EMA_CROSSOVER.name());
        req.setSymbol(symbol);
        req.setTimeframe(timeframe);
        req.setModelKey(modelKey);
        req.setSchemaHash(schemaHash);
        req.setFeatureSchema(FEATURE_SCHEMA);
        req.setRows(rows);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reason", blankToDefault(reason, "prepare_start_train"));
        params.put("rows", rows.size());
        params.put("candles", points.size());
        params.put("datasetSource", "selected_candles");
        params.put("exchange", exchange);
        params.put("network", networkName);
        params.put("emaFast", effectiveCfg.getEmaFast());
        params.put("emaSlow", effectiveCfg.getEmaSlow());
        params.put("confirmBars", effectiveCfg.getConfirmBars());
        params.put("takeProfitPct", tpPct);
        params.put("stopLossPct", slPct);
        req.setParams(params);

        log.info("🧠 EMA TRAIN START chatId={} ex={} net={} sym={} tf={} rows={} candles={} schemaHash={} modelKey={} reason={} tpPct={} slPct={}",
                chatId,
                exchange,
                networkName,
                symbol,
                timeframe,
                rows.size(),
                points.size(),
                schemaHash,
                modelKey,
                blankToDefault(reason, "prepare_start_train"),
                tpPct,
                slPct);

        MlTrainResponse response;
        try {
            response = client.train(req);
        } catch (Exception e) {
            log.warn("🧠 EMA TRAIN FAIL chatId={} ex={} net={} sym={} tf={} err={}",
                    chatId, exchange, networkName, symbol, timeframe, e.toString());
            return new PrepareResult(false, false, rows.size(), modelKey, null, schemaHash, "train_exception");
        }

        if (response == null) {
            return new PrepareResult(false, false, rows.size(), modelKey, null, schemaHash, "train_null");
        }
        if (!response.isOk()) {
            return new PrepareResult(false, false, rows.size(), modelKey, null, schemaHash,
                    blankToDefault(readString(response, "getError", "error"), "train_not_ok"));
        }

        String modelVersion = blankToDefault(readString(response, "getModelVersion", "getVersion", "modelVersion", "version"),
                String.valueOf(System.currentTimeMillis()));
        String finalSchemaHash = blankToDefault(readString(response, "getSchemaHash", "schemaHash"), schemaHash);
        BigDecimal resolvedGateMinProb = resolveGateMinProb(response, ss.getGateMinProb());
        String metricsSummary = buildMetricsSummary(response);

        boolean applied = false;
        try {
            StrategySettings fresh = strategySettingsService.getOrCreate(chatId, StrategyType.EMA_CROSSOVER);
            fresh.setMlModelKey(modelKey);
            fresh.setMlModelVersion(modelVersion);
            fresh.setMlSchemaHash(finalSchemaHash);
            if (fresh.isAiMode()) {
                fresh.setMlGateEnabled(true);
                fresh.setGateMinProb(resolveGateMinProb(response, fresh.getGateMinProb()));
            }
            strategySettingsService.save(fresh);
            applied = true;
        } catch (Exception e) {
            log.warn("🧠 EMA TRAIN apply settings failed chatId={} err={}", chatId, e.toString());
        }

        log.info("🧠 EMA TRAIN DONE chatId={} ex={} net={} sym={} tf={} applied={} modelKey={} ver={} rows={} pos={} neg={} posRate={} gateThr={} tpPct={} slPct={} metrics={}",
                chatId,
                exchange,
                networkName,
                symbol,
                timeframe,
                applied,
                modelKey,
                modelVersion,
                rows.size(),
                trainingStats.positives(),
                trainingStats.negatives(),
                trainingStats.positiveRate(),
                resolvedGateMinProb != null ? resolvedGateMinProb.toPlainString() : "null",
                tpPct,
                slPct,
                metricsSummary);

        return new PrepareResult(true, applied, rows.size(), modelKey, modelVersion, finalSchemaHash, "ok");
    }

    public List<CandlePoint> loadCandlePoints(Long chatId, StrategySettings ss, int requestedLimit) {
        if (chatId == null || chatId <= 0 || ss == null) {
            return List.of();
        }

        String exchange = normUpper(ss.getExchangeName());
        NetworkType network = ss.getNetworkType();
        String symbol = normUpper(ss.getSymbol());
        String timeframe = normLower(ss.getTimeframe());
        int limit = clampInt(requestedLimit > 0 ? requestedLimit : DEFAULT_LIMIT, MIN_LIMIT, MAX_LIMIT);

        if (exchange == null || network == null || symbol == null || timeframe == null) {
            return List.of();
        }

        tryWarmupHistory(chatId, StrategyType.EMA_CROSSOVER, exchange, network, symbol, timeframe, limit);

        Object service = getBeanByName("marketDataStreamService");
        if (service == null) service = getBeanByName("marketStreamService");
        if (service == null) return List.of();

        Object raw = tryInvokeCandleLoader(service, chatId, StrategyType.EMA_CROSSOVER, exchange, network, symbol, timeframe, limit);
        List<Object> normalized = normalizeCandleResult(raw, limit);
        if (normalized.isEmpty()) {
            tryPreloadFromExchange(chatId, StrategyType.EMA_CROSSOVER, exchange, network, symbol, timeframe, limit);
            raw = tryInvokeCandleLoader(service, chatId, StrategyType.EMA_CROSSOVER, exchange, network, symbol, timeframe, limit);
            normalized = normalizeCandleResult(raw, limit);
        }

        List<CandlePoint> points = new ArrayList<>(normalized.size());
        for (Object candle : normalized) {
            CandlePoint point = toCandlePoint(candle);
            if (point != null) {
                points.add(point);
            }
        }
        points.sort(Comparator.comparingLong(CandlePoint::openTimeMs));
        return points;
    }

    private List<Map<String, Object>> buildRows(Long chatId,
                                                String symbol,
                                                String exchange,
                                                String network,
                                                String timeframe,
                                                EmaCrossoverStrategySettings cfg,
                                                List<CandlePoint> points,
                                                BigDecimal tpPct,
                                                BigDecimal slPct) {

        List<Map<String, Object>> rows = new ArrayList<>();
        if (points == null || points.isEmpty() || cfg == null) {
            return rows;
        }

        int fastPeriod = clampInt(cfg.getEmaFast() != null ? cfg.getEmaFast() : 9, 1, 300);
        int slowPeriod = clampInt(cfg.getEmaSlow() != null ? cfg.getEmaSlow() : 21, 2, 600);
        if (slowPeriod <= fastPeriod) slowPeriod = fastPeriod + 1;

        int confirmBars = clampInt(cfg.getConfirmBars() != null ? cfg.getConfirmBars() : 1, 1, 10);
        double maxSpreadPct = cfg.getMaxSpreadPct() != null ? Math.max(0.0d, cfg.getMaxSpreadPct()) : 0.08d;

        Double fast = null;
        Double slow = null;
        Double prevFast = null;
        Double prevSlow = null;
        int bullishConfirmBars = 0;
        int bearishConfirmBars = 0;
        Deque<Double> recent = new ArrayDeque<>();

        int horizon = Math.max(8, Math.min(48, slowPeriod * 2));
        double tpPctValue = sanitizePct(tpPct, DEFAULT_TP_PCT).doubleValue();
        double slPctValue = sanitizePct(slPct, DEFAULT_SL_PCT).doubleValue();

        for (int i = 0; i < points.size(); i++) {
            CandlePoint p = points.get(i);
            double price = p.close();

            prevFast = fast;
            prevSlow = slow;
            fast = nextEma(fast, price, fastPeriod);
            slow = nextEma(slow, price, slowPeriod);

            recent.addLast(price);
            while (recent.size() > 6) recent.removeFirst();

            if (i + 1 < slowPeriod || prevFast == null || prevSlow == null || slow == null || fast == null) {
                continue;
            }

            boolean bullRegime = fast > slow;
            boolean bearRegime = fast < slow;
            if (bullRegime) {
                bullishConfirmBars++;
                bearishConfirmBars = 0;
            } else if (bearRegime) {
                bearishConfirmBars++;
                bullishConfirmBars = 0;
            }

            double spreadPct = safePct(Math.abs(fast - slow), slow);
            double priceVsFastPct = safePct(price - fast, fast);
            double priceVsSlowPct = safePct(price - slow, slow);
            double fastSlopePct = safePct(fast - prevFast, prevFast);
            double slowSlopePct = safePct(slow - prevSlow, prevSlow);
            double ret1Pct = returnPct(recent, 1);
            double ret3Pct = returnPct(recent, 3);
            double ret5Pct = returnPct(recent, 5);
            double volatilityPct = volatilityPct(recent);
            int crossUp = (prevFast <= prevSlow && fast > slow) ? 1 : 0;
            int crossDown = (prevFast >= prevSlow && fast < slow) ? 1 : 0;

            Integer label = simulateLabel(points, i, tpPctValue, slPctValue, horizon);
            if (label == null) {
                continue;
            }

            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("bullRegime", bullRegime ? 1 : 0);
            row.put("bullishConfirmBars", bullishConfirmBars);
            row.put("confirmBars", confirmBars);
            row.put("crossDown", crossDown);
            row.put("crossUp", crossUp);
            row.put("emaFast", fastPeriod);
            row.put("emaSlow", slowPeriod);
            row.put("fast", fast);
            row.put("fastSlopePct", fastSlopePct);
            row.put("maxSpreadPct", maxSpreadPct);
            row.put("price", price);
            row.put("priceVsFastPct", priceVsFastPct);
            row.put("priceVsSlowPct", priceVsSlowPct);
            row.put("ret1Pct", ret1Pct);
            row.put("ret3Pct", ret3Pct);
            row.put("ret5Pct", ret5Pct);
            row.put("slow", slow);
            row.put("slowSlopePct", slowSlopePct);
            row.put("spreadPct", spreadPct);
            row.put("volatilityPct", volatilityPct);
            row.put("label", label);
            row.put("chatId", chatId);
            row.put("strategyType", StrategyType.EMA_CROSSOVER.name());
            row.put("symbol", symbol);
            row.put("exchange", exchange);
            row.put("network", network);
            row.put("timeframe", timeframe);
            row.put("ts", p.closeTimeMs());
            rows.add(row);
        }

        return rows;
    }

    private Integer simulateLabel(List<CandlePoint> points,
                                  int entryIndex,
                                  double tpPct,
                                  double slPct,
                                  int horizon) {
        if (entryIndex < 0 || entryIndex >= points.size()) return null;
        double entry = points.get(entryIndex).close();
        if (!Double.isFinite(entry) || entry <= 0.0d) return null;

        double tp = entry * (1.0d + tpPct / 100.0d);
        double sl = entry * (1.0d - slPct / 100.0d);

        int end = Math.min(points.size() - 1, entryIndex + Math.max(2, horizon));
        for (int i = entryIndex + 1; i <= end; i++) {
            CandlePoint p = points.get(i);
            if (p.low() <= sl) return 0;
            if (p.high() >= tp) return 1;
        }

        return points.get(end).close() >= entry ? 1 : 0;
    }


    private TrainingStats summarizeRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return new TrainingStats(0, 0, BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP));
        }

        int positives = 0;
        int negatives = 0;
        for (Map<String, Object> row : rows) {
            Integer label = toInt(row != null ? row.get("label") : null);
            if (label != null && label > 0) {
                positives++;
            } else {
                negatives++;
            }
        }

        BigDecimal total = BigDecimal.valueOf(Math.max(1, positives + negatives));
        BigDecimal positiveRate = BigDecimal.valueOf(positives)
                .divide(total, 6, RoundingMode.HALF_UP);

        return new TrainingStats(positives, negatives, positiveRate);
    }

    private BigDecimal resolveGateMinProb(Object response, BigDecimal current) {
        BigDecimal threshold = readBigDecimal(response,
                "getBestThreshold",
                "getThreshold",
                "getGateThreshold",
                "bestThreshold",
                "threshold",
                "gateThreshold");

        if (threshold == null) {
            Object metrics = readBeanValue(response, "getMetrics", "metrics");
            if (metrics instanceof Map<?, ?> map) {
                threshold = firstNonNull(
                        toBigDecimal(map.get("bestThreshold")),
                        toBigDecimal(map.get("threshold")),
                        toBigDecimal(map.get("gateThreshold"))
                );
            }
        }

        if (threshold == null) {
            threshold = parseMetricFromMetricsJson(response,
                    "bestThreshold",
                    "threshold",
                    "gateThreshold");
        }

        return sanitizeGateProb(threshold != null ? threshold : current, new BigDecimal("0.550000"));
    }

    private String buildMetricsSummary(Object response) {
        BigDecimal auc = readMetric(response, "auc", "rocAuc", "valAuc");
        BigDecimal f1 = readMetric(response, "f1", "f1Score", "valF1");
        BigDecimal precision = readMetric(response, "precision", "valPrecision");
        BigDecimal recall = readMetric(response, "recall", "valRecall");

        if (auc == null && f1 == null && precision == null && recall == null) {
            return "n/a";
        }

        return "auc=" + fmtMetric(auc)
                + ", f1=" + fmtMetric(f1)
                + ", precision=" + fmtMetric(precision)
                + ", recall=" + fmtMetric(recall);
    }

    private BigDecimal readMetric(Object response, String... keys) {
        if (response == null || keys == null || keys.length == 0) {
            return null;
        }

        List<String> directMethods = new ArrayList<>();
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            directMethods.add(key);
            directMethods.add("get" + Character.toUpperCase(key.charAt(0)) + key.substring(1));
        }
        BigDecimal direct = readBigDecimal(response, directMethods.toArray(new String[0]));
        if (direct != null) return direct;

        Object metrics = readBeanValue(response, "getMetrics", "metrics");
        if (metrics instanceof Map<?, ?> map) {
            for (String key : keys) {
                BigDecimal value = toBigDecimal(map.get(key));
                if (value != null) return value;
            }
        }

        return parseMetricFromMetricsJson(response, keys);
    }

    private BigDecimal parseMetricFromMetricsJson(Object response, String... keys) {
        if (response == null || keys == null || keys.length == 0) {
            return null;
        }

        String metricsJson = readString(response, "getMetricsJson", "metricsJson");
        if (metricsJson == null || metricsJson.isBlank()) {
            return null;
        }

        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            BigDecimal value = extractNumberFromJson(metricsJson, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal extractNumberFromJson(String json, String key) {
        if (json == null || json.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        try {
            Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
            Matcher m = p.matcher(json);
            if (m.find()) {
                return new BigDecimal(m.group(1));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private int resolveMinSamples() {
        MlTrainProperties props = trainPropertiesProvider != null ? trainPropertiesProvider.getIfAvailable() : null;
        int min = props != null ? props.getMinSamples() : 200;
        return Math.max(100, min);
    }

    private String computeSchemaHash() {
        try {
            String joined = String.join("|", FEATURE_SCHEMA);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private Object getBeanByName(String name) {
        try {
            return applicationContext != null && applicationContext.containsBean(name)
                    ? applicationContext.getBean(name)
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void tryPreloadFromExchange(Long chatId,
                                        StrategyType type,
                                        String exchange,
                                        NetworkType network,
                                        String symbol,
                                        String timeframe,
                                        int limit) {
        try {
            ExchangeClientFactory factory = applicationContext != null ? applicationContext.getBean(ExchangeClientFactory.class) : null;
            MarketDataStreamService streamService = applicationContext != null ? applicationContext.getBean(MarketDataStreamService.class) : null;
            if (factory == null || streamService == null) {
                return;
            }

            ExchangeClient client = factory.get(exchange, network);
            if (client == null) {
                return;
            }

            List<ExchangeClient.Kline> klines = client.getKlines(symbol, timeframe, limit);
            if (klines == null || klines.isEmpty()) {
                return;
            }

            List<Candle> preload = klines.stream()
                    .filter(k -> k != null)
                    .sorted(Comparator.comparingLong(ExchangeClient.Kline::openTime))
                    .map(k -> new Candle(
                            k.openTime(),
                            k.open(),
                            k.high(),
                            k.low(),
                            k.close(),
                            k.volume(),
                            true
                    ))
                    .toList();

            if (!preload.isEmpty()) {
                streamService.putCandles(chatId, type, exchange, network, symbol, timeframe, preload);
                log.info("🧠 EMA preload from exchange chatId={} ex={} net={} sym={} tf={} candles={}",
                        chatId, exchange, network, symbol, timeframe, preload.size());
            }
        } catch (Exception e) {
            log.debug("EMA preload from exchange skipped chatId={} ex={} net={} sym={} tf={} err={}",
                    chatId, exchange, network, symbol, timeframe, e.toString());
        }
    }

    private void tryWarmupHistory(Long chatId,
                                  StrategyType type,
                                  String exchange,
                                  NetworkType network,
                                  String symbol,
                                  String timeframe,
                                  int candlesLimit) {
        Object warmupService = getBeanByName("historyWarmupService");
        if (warmupService == null) return;

        List<Object[]> candidates = List.of(
                new Object[]{chatId, type, exchange, network, symbol, timeframe, candlesLimit},
                new Object[]{chatId, type, exchange, network, symbol, timeframe, Integer.valueOf(candlesLimit)},
                new Object[]{chatId, type, exchange, network, symbol, timeframe},
                new Object[]{exchange, network, symbol, timeframe, candlesLimit}
        );

        for (String methodName : List.of("warmup", "ensureWarmup", "warmupIfNeeded", "ensureHistory")) {
            for (Object[] args : candidates) {
                Object ignored = invokeCompatible(warmupService, methodName, args);
                if (ignored != null || hasCompatibleMethod(warmupService, methodName, args)) {
                    return;
                }
            }
        }
    }

    private Object tryInvokeCandleLoader(Object service,
                                         Long chatId,
                                         StrategyType type,
                                         String exchange,
                                         NetworkType network,
                                         String symbol,
                                         String timeframe,
                                         int candlesLimit) {
        if (service == null) return null;

        List<Object[]> candidates = List.of(
                new Object[]{chatId, type, exchange, network, symbol, timeframe, candlesLimit},
                new Object[]{chatId, type, symbol, timeframe, exchange, network, candlesLimit},
                new Object[]{chatId, type, symbol, timeframe, candlesLimit},
                new Object[]{chatId, symbol, timeframe, candlesLimit},
                new Object[]{symbol, timeframe, candlesLimit}
        );

        for (String methodName : List.of(
                "getCachedCandles",
                "getRecentCandles",
                "getCandles",
                "loadCandles",
                "getCandlesSnapshot",
                "snapshotCandles",
                "findCandles",
                "readCandles"
        )) {
            for (Object[] args : candidates) {
                Object value = invokeCompatible(service, methodName, args);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private List<Object> normalizeCandleResult(Object value, int limit) {
        if (value == null) return List.of();

        List<Object> out = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) out.add(item);
            }
        } else if (value.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < n; i++) {
                Object item = java.lang.reflect.Array.get(value, i);
                if (item != null) out.add(item);
            }
        } else {
            Object nested = invokeCompatible(value, "getCandles");
            if (nested == null) nested = invokeCompatible(value, "candles");
            if (nested == null) nested = invokeCompatible(value, "items");
            if (nested != null && nested != value) {
                return normalizeCandleResult(nested, limit);
            }
        }

        out.sort(Comparator.comparingLong(this::candleOpenTimeMsSafe));
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(out.size() - limit, out.size()));
        }
        return out;
    }

    private CandlePoint toCandlePoint(Object candle) {
        if (candle == null) return null;

        Double open = toDouble(readBeanValue(candle, "getOpen", "open"));
        Double high = toDouble(readBeanValue(candle, "getHigh", "high"));
        Double low = toDouble(readBeanValue(candle, "getLow", "low"));
        Double close = toDouble(readBeanValue(candle, "getClose", "close"));
        Long openTime = toLong(readBeanValue(candle, "getOpenTime", "openTime", "getTimestamp", "timestamp", "getOpenTimeMs", "openTimeMs"));
        Long closeTime = toLong(readBeanValue(candle, "getCloseTime", "closeTime", "getCloseTimeMs", "closeTimeMs"));

        if (open == null || high == null || low == null || close == null) return null;
        if (!Double.isFinite(open) || !Double.isFinite(high) || !Double.isFinite(low) || !Double.isFinite(close)) return null;
        if (low <= 0.0d || high < low) return null;

        if (openTime == null) openTime = 0L;
        if (closeTime == null || closeTime <= 0L) closeTime = openTime;

        return new CandlePoint(open, high, low, close, openTime, closeTime);
    }

    private long candleOpenTimeMsSafe(Object candle) {
        CandlePoint p = toCandlePoint(candle);
        return p != null ? p.openTimeMs() : Long.MIN_VALUE;
    }

    private static double nextEma(Double prev, double price, int period) {
        if (period <= 1 || prev == null) return price;
        double alpha = 2.0d / (period + 1.0d);
        return price * alpha + prev * (1.0d - alpha);
    }

    private static double returnPct(Deque<Double> recent, int barsBack) {
        if (recent == null || recent.isEmpty()) return 0.0d;
        List<Double> list = new ArrayList<>(recent);
        if (list.size() <= barsBack) return 0.0d;
        double last = list.get(list.size() - 1);
        double prev = list.get(list.size() - 1 - barsBack);
        return safePct(last - prev, prev);
    }

    private static double volatilityPct(Deque<Double> recent) {
        if (recent == null || recent.size() < 3) return 0.0d;
        double mean = 0.0d;
        int count = 0;
        for (Double v : recent) {
            if (v == null || !Double.isFinite(v) || v <= 0.0d) continue;
            mean += v;
            count++;
        }
        if (count < 3) return 0.0d;
        mean /= count;
        if (mean <= 0.0d || !Double.isFinite(mean)) return 0.0d;
        double var = 0.0d;
        for (Double v : recent) {
            if (v == null || !Double.isFinite(v) || v <= 0.0d) continue;
            double d = v - mean;
            var += d * d;
        }
        var /= count;
        double std = Math.sqrt(var);
        return (std / mean) * 100.0d;
    }

    private static double safePct(double numerator, double base) {
        if (!Double.isFinite(numerator) || !Double.isFinite(base) || Math.abs(base) < 1e-12d) return 0.0d;
        double v = (numerator / base) * 100.0d;
        return Double.isFinite(v) ? v : 0.0d;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Object readBeanValue(Object target, String... methodNames) {
        if (target == null || methodNames == null) return null;
        for (String methodName : methodNames) {
            if (methodName == null || methodName.isBlank()) continue;
            try {
                var m = target.getClass().getMethod(methodName);
                return m.invoke(target);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private boolean hasCompatibleMethod(Object target, String methodName, Object[] args) {
        if (target == null || methodName == null) return false;
        for (var m : target.getClass().getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != (args != null ? args.length : 0)) continue;
            if (areArgumentsCompatible(pt, args)) return true;
        }
        return false;
    }

    private Object invokeCompatible(Object target, String methodName, Object... args) {
        if (target == null || methodName == null) return null;
        for (var m : target.getClass().getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != (args != null ? args.length : 0)) continue;
            if (!areArgumentsCompatible(pt, args)) continue;
            try {
                return m.invoke(target, coerceArgs(pt, args));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private boolean areArgumentsCompatible(Class<?>[] types, Object[] args) {
        if (types == null) return args == null || args.length == 0;
        if (args == null) return types.length == 0;
        if (types.length != args.length) return false;

        for (int i = 0; i < types.length; i++) {
            Class<?> t = wrap(types[i]);
            Object a = args[i];
            if (a == null) {
                if (types[i].isPrimitive()) return false;
                continue;
            }
            if (t.isInstance(a)) continue;
            if (Number.class.isAssignableFrom(t) && a instanceof Number) continue;
            if (t == String.class) continue;
            if (t == Boolean.class && (a instanceof Boolean || a instanceof Number || a instanceof String)) continue;
            if (Enum.class.isAssignableFrom(t) && (a instanceof Enum<?> || a instanceof String)) continue;
            return false;
        }
        return true;
    }

    private Object[] coerceArgs(Class<?>[] types, Object[] args) {
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            out[i] = coerceArg(types[i], args[i]);
        }
        return out;
    }

    private Object coerceArg(Class<?> type, Object arg) {
        if (arg == null) return null;
        Class<?> t = wrap(type);
        if (t.isInstance(arg)) return arg;
        if (t == String.class) return String.valueOf(arg);
        if (Enum.class.isAssignableFrom(t)) {
            try {
                String name = (arg instanceof Enum<?> e) ? e.name() : String.valueOf(arg);
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object en = Enum.valueOf((Class<? extends Enum>) t.asSubclass(Enum.class), name);
                return en;
            } catch (Exception ignored) {
                return null;
            }
        }
        if (Number.class.isAssignableFrom(t)) {
            if (arg instanceof Number n) {
                if (t == Integer.class) return n.intValue();
                if (t == Long.class) return n.longValue();
                if (t == Double.class) return n.doubleValue();
                if (t == Float.class) return n.floatValue();
                if (t == BigDecimal.class) return BigDecimal.valueOf(n.doubleValue());
            }
            try {
                String s = String.valueOf(arg).trim();
                if (t == Integer.class) return Integer.parseInt(s);
                if (t == Long.class) return Long.parseLong(s);
                if (t == Double.class) return Double.parseDouble(s);
                if (t == Float.class) return Float.parseFloat(s);
                if (t == BigDecimal.class) return new BigDecimal(s);
            } catch (Exception ignored) {
                return null;
            }
        }
        if (t == Boolean.class) {
            if (arg instanceof Boolean b) return b;
            if (arg instanceof Number n) return n.intValue() != 0;
            return Boolean.parseBoolean(String.valueOf(arg));
        }
        return arg;
    }

    private Class<?> wrap(Class<?> type) {
        if (type == null) return Object.class;
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }


    private static BigDecimal readBigDecimal(Object target, String... methods) {
        if (target == null || methods == null) return null;
        for (String method : methods) {
            if (method == null || method.isBlank()) continue;
            try {
                var m = target.getClass().getMethod(method);
                Object v = m.invoke(target);
                BigDecimal bd = toBigDecimal(v);
                if (bd != null) return bd;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer toInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) return null;
        for (T value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static BigDecimal sanitizeGateProb(BigDecimal value, BigDecimal def) {
        BigDecimal v = value != null ? value : def;
        if (v == null) v = new BigDecimal("0.550000");
        v = v.setScale(6, RoundingMode.HALF_UP);
        if (v.compareTo(new BigDecimal("0.050000")) < 0) return new BigDecimal("0.050000");
        if (v.compareTo(new BigDecimal("0.950000")) > 0) return new BigDecimal("0.950000");
        return v;
    }

    private static String fmtMetric(BigDecimal value) {
        if (value == null) return "null";
        try {
            return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd.doubleValue();
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normUpper(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static String normLower(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static String blankToDefault(String s, String def) {
        if (s == null || s.isBlank()) return def;
        return s.trim();
    }

    private static String readString(Object target, String... methods) {
        if (target == null || methods == null) return null;
        for (String method : methods) {
            if (method == null || method.isBlank()) continue;
            try {
                var m = target.getClass().getMethod(method);
                Object v = m.invoke(target);
                if (v == null) continue;
                String s = String.valueOf(v).trim();
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static BigDecimal sanitizePct(BigDecimal value, BigDecimal def) {
        BigDecimal pct = value != null ? value : def;
        if (pct.signum() <= 0) pct = def;
        return pct.setScale(4, RoundingMode.HALF_UP);
    }
}


