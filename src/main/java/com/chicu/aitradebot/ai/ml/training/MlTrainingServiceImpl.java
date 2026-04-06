package com.chicu.aitradebot.ai.ml.training;

import com.chicu.aitradebot.ai.ml.MlClient;
import com.chicu.aitradebot.ai.ml.MlGateway;
import com.chicu.aitradebot.ai.ml.MlTrainProperties;
import com.chicu.aitradebot.ai.ml.artifacts.MlModelArtifactEntity;
import com.chicu.aitradebot.ai.ml.artifacts.MlModelArtifactRepository;
import com.chicu.aitradebot.ai.ml.dataset.MlSampleEntity;
import com.chicu.aitradebot.ai.ml.dataset.MlSampleRepository;
import com.chicu.aitradebot.ai.ml.dto.MlTrainRequest;
import com.chicu.aitradebot.ai.ml.dto.MlTrainResponse;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.market.stream.MarketDataStreamService;
import com.chicu.aitradebot.market.model.Candle;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.strategy.windowscalping.WindowScalpingStrategySettingsService;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.events.StrategySettingsUpdatedEvent;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlTrainingServiceImpl implements MlTrainingService {

    private static final Set<String> META_KEYS = Set.of(
            "chatId",
            "strategyType",
            "strategy",
            "symbol",
            "exchange",
            "network",
            "timeframe",
            "modelKey",
            "schemaHash",
            "featureOrder",
            "featureSchema",
            "schema",
            "schemaFields",
            "ts",
            "tsMs"
    );

    private static final Set<String> LABEL_KEYS = Set.of(
            "label", "y", "Y", "target", "class", "win"
    );

    private static final Map<String, String> WINDOW_STRATEGY_PARAM_ALIASES = Map.of(
            "slFactor", "autoSlFromRangeFactor",
            "tpFactor", "autoTpFromRangeFactor",
            "minRiskReward", "autoMinRiskReward",
            "slMinPct", "autoSlMinPct",
            "slMaxPct", "autoSlMaxPct",
            "tpMinPct", "autoTpMinPct",
            "tpMaxPct", "autoTpMaxPct",
            "tpMlBoost", "autoTpMlBoostFactor",
            "tpWeakFactor", "autoTpWeakSignalFactor"
    );

    private final MlTrainProperties props;
    private final MlSampleRepository sampleRepo;
    private final MlModelArtifactRepository artifactRepo;
    private final StrategySettingsService strategySettingsService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<MlClient> mlClientProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationContext applicationContext;

    private final Map<String, Instant> lastTrainAt = new ConcurrentHashMap<>();

    @Override
    public MlTrainingResult trainNow(Long chatId, StrategyType type, String reason) {
        if (props == null || !props.isEnabled()) {
            log.warn("🧠 TRAIN SKIP: training disabled");
            return new MlTrainingResult(false, false, null, null, null, "training_disabled");
        }
        if (chatId == null || chatId <= 0 || type == null) {
            log.warn("🧠 TRAIN SKIP: bad args chatId={} type={}", chatId, type);
            return new MlTrainingResult(false, false, null, null, null, "bad_args");
        }

        MlClient mlClient = mlClientProvider != null ? mlClientProvider.getIfAvailable() : null;
        if (mlClient == null) {
            log.warn("🧠 TRAIN SKIP: MlClient missing");
            return new MlTrainingResult(false, false, null, null, null, "ml_client_missing");
        }

        StrategySettings ss;
        try {
            ss = strategySettingsService.getSettings(chatId, type);
            if (ss == null) {
                ss = strategySettingsService.getOrCreate(chatId, type);
            }
        } catch (Exception e) {
            log.warn("🧠 TRAIN settings load failed chatId={} type={} err={}", chatId, type, e.toString());
            return new MlTrainingResult(false, false, null, null, null, "strategy_settings_missing");
        }
        if (ss == null) {
            return new MlTrainingResult(false, false, null, null, null, "strategy_settings_missing");
        }

        String reasonNorm = normTrim(reason);
        if (reasonNorm == null) reasonNorm = "auto";

        Instant now = Instant.now();
        Instant from = now.minus(Math.max(1, props.getLookbackDays()), ChronoUnit.DAYS);

        String symbol = normUpper(ss.getSymbol());
        String timeframe = normLower(ss.getTimeframe());
        String exchange = normUpper(stringOf(ss.getExchangeName()));
        String network = normUpper(stringOf(ss.getNetworkType()));

        if (symbol == null || timeframe == null || exchange == null || network == null) {
            Context inferred = inferContextFromRecentSamples(chatId, type, from);
            if (symbol == null) symbol = inferred.symbol();
            if (timeframe == null) timeframe = inferred.timeframe();
            if (exchange == null) exchange = inferred.exchange();
            if (network == null) network = inferred.network();
        }

        if (symbol == null) return new MlTrainingResult(false, false, null, null, null, "symbol_missing");
        if (timeframe == null) return new MlTrainingResult(false, false, null, null, null, "timeframe_missing");
        if (exchange == null) return new MlTrainingResult(false, false, null, null, null, "exchange_missing");
        if (network == null) return new MlTrainingResult(false, false, null, null, null, "network_missing");

        String modelKey = MlGateway.buildContextModelKey(type, exchange, network, symbol, timeframe);

        String cooldownKey = cooldownKey(modelKey);
        Instant last = lastTrainAt.get(cooldownKey);
        long cooldownMinutes = Math.max(0, props.getCooldownMinutes());
        if (last != null && cooldownMinutes > 0) {
            long passed = ChronoUnit.MINUTES.between(last, now);
            if (passed < cooldownMinutes) {
                log.info("🧠 TRAIN SKIP: cooldown modelKey={} passed={}m need={}m", modelKey, passed, cooldownMinutes);
                return new MlTrainingResult(false, false, modelKey, null, normTrim(ss.getMlSchemaHash()), "cooldown");
            }
        }

        List<MlSampleEntity> contextSamples = safeFindForTrainingByContext(type, symbol, timeframe, exchange, network, from);
        if (contextSamples.isEmpty()) {
            contextSamples = safeFindRecent(chatId, type, from);
        }
        if (contextSamples.isEmpty()) {
            log.warn("🧠 TRAIN SKIP: no recent samples type={} ex={} net={} sym={} tf={} from={}",
                    type, exchange, network, symbol, timeframe, from);
            return new MlTrainingResult(false, false, modelKey, null, normTrim(ss.getMlSchemaHash()), "no_samples");
        }

        List<MlSampleEntity> filtered = new ArrayList<>();
        for (MlSampleEntity s : contextSamples) {
            if (!matchesContext(s, type, symbol, timeframe, exchange, network)) continue;
            if (s.getFeaturesJson() == null || !s.getFeaturesJson().isObject()) continue;
            if (labelToIntOrNull(s.getLabel()) == null) continue;
            filtered.add(s);
        }

        if (filtered.isEmpty()) {
            return new MlTrainingResult(false, false, modelKey, null, normTrim(ss.getMlSchemaHash()), "no_context_samples");
        }

        Map<String, Integer> schemaCounts = new LinkedHashMap<>();
        Map<String, List<MlSampleEntity>> schemaGroups = new LinkedHashMap<>();
        for (MlSampleEntity sample : filtered) {
            String sampleSchemaHash = resolveSampleSchemaHash(sample);
            if (sampleSchemaHash == null) continue;
            schemaCounts.merge(sampleSchemaHash, 1, Integer::sum);
            schemaGroups.computeIfAbsent(sampleSchemaHash, k -> new ArrayList<>()).add(sample);
        }

        String preferredSchemaHash = normTrim(ss.getMlSchemaHash());
        String selectedSchemaHash = chooseSchemaHash(preferredSchemaHash, schemaCounts);
        if (selectedSchemaHash != null && schemaGroups.containsKey(selectedSchemaHash)) {
            filtered = schemaGroups.get(selectedSchemaHash);
        }

        int rowsLimit = Math.max(100, props.getRowsLimit());
        filtered.sort(Comparator.comparing(MlTrainingServiceImpl::sampleTimeMs, Comparator.nullsLast(Comparator.reverseOrder())));
        if (filtered.size() > rowsLimit) {
            filtered = new ArrayList<>(filtered.subList(0, rowsLimit));
        }
        filtered.sort(Comparator.comparing(MlTrainingServiceImpl::sampleTimeMs, Comparator.nullsLast(Comparator.naturalOrder())));

        List<String> featureSchema = resolveFeatureSchema(filtered);
        if (featureSchema == null || featureSchema.isEmpty()) {
            return new MlTrainingResult(false, false, modelKey, null, null, "feature_schema_missing");
        }

        String computedSchemaHash = computeSchemaHash(featureSchema);
        List<Map<String, Object>> rows = toRows(filtered, featureSchema);
        if (rows.size() < props.getMinSamples()) {
            log.warn("🧠 TRAIN SKIP: not enough compatible rows modelKey={} samples={} minSamples={} schemaHash={}",
                    modelKey, rows.size(), props.getMinSamples(), computedSchemaHash);
            return new MlTrainingResult(false, false, modelKey, null, computedSchemaHash, "not_enough_samples=" + rows.size());
        }

        boolean settingsChangedBeforeTrain = false;
        if (!Objects.equals(normTrim(ss.getMlModelKey()), modelKey)) {
            ss.setMlModelKey(modelKey);
            settingsChangedBeforeTrain = true;
        }
        if (!Objects.equals(normTrim(ss.getMlSchemaHash()), computedSchemaHash)) {
            ss.setMlSchemaHash(computedSchemaHash);
            settingsChangedBeforeTrain = true;
        }
        if (settingsChangedBeforeTrain) {
            try {
                ss = strategySettingsService.save(ss);
            } catch (Exception e) {
                log.warn("🧠 TRAIN pre-save settings failed chatId={} type={} err={}", chatId, type, e.toString());
            }
        }

        MlTrainRequest req = new MlTrainRequest();
        req.setChatId(chatId);
        req.setStrategyType(type.name());
        req.setSymbol(symbol);
        req.setTimeframe(timeframe);
        req.setModelKey(modelKey);
        req.setSchemaHash(computedSchemaHash);
        req.setFeatureSchema(featureSchema);
        req.setRows(rows);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reason", reasonNorm);
        params.put("rows", rows.size());
        params.put("from", from.toEpochMilli());
        params.put("to", now.toEpochMilli());
        params.put("modelKey", modelKey);
        params.put("schemaHash", computedSchemaHash);
        params.put("exchange", exchange);
        params.put("network", network);
        params.put("cohortUsers", estimateDistinctUsers(filtered));
        req.setParams(params);

        log.info("🧠 TRAIN START type={} ex={} net={} sym={} tf={} rows={} schemaSize={} schemaHash={} modelKey={} reason={}",
                type, exchange, network, symbol, timeframe, rows.size(), featureSchema.size(), computedSchemaHash, modelKey, reasonNorm);

        MlTrainResponse resp;
        try {
            resp = mlClient.train(req);
        } catch (Exception e) {
            log.warn("🧠 TRAIN exception type={} ex={} net={} sym={} tf={} err={}",
                    type, exchange, network, symbol, timeframe, e.toString(), e);
            return new MlTrainingResult(false, false, modelKey, null, computedSchemaHash, "train_exception");
        }

        if (resp == null) {
            return new MlTrainingResult(false, false, modelKey, null, computedSchemaHash, "train_null");
        }
        if (!resp.isOk()) {
            String error = normTrim(resp.getError()) != null ? resp.getError() : "train_not_ok";
            return new MlTrainingResult(false, false, modelKey, null, computedSchemaHash, error);
        }

        String responseModelKey = modelKey;
        String responseModelVersion = normTrim(resp.getModelVersion());
        String responseSchemaHash = normTrim(resp.getSchemaHash());
        String finalSchemaHash = responseSchemaHash != null ? responseSchemaHash : computedSchemaHash;

        saveArtifactSafe(chatId, type, symbol, timeframe, responseModelKey, responseModelVersion, finalSchemaHash, resp.getMetricsJson(), now);

        boolean applied = false;
        try {
            AdvancedControlMode mode = ss.getAdvancedControlMode() != null ? ss.getAdvancedControlMode() : AdvancedControlMode.MANUAL;
            ss.setMlModelKey(responseModelKey);
            ss.setMlModelVersion(responseModelVersion);
            ss.setMlSchemaHash(finalSchemaHash);
            BigDecimal minProb = ss.getGateMinProb();
            if ((minProb == null || minProb.signum() <= 0) && props.getThresholdAutoEnable() > 0) {
                ss.setGateMinProb(BigDecimal.valueOf(props.getThresholdAutoEnable()).setScale(6, RoundingMode.HALF_UP));
            }
            if (mode == AdvancedControlMode.AI || mode == AdvancedControlMode.HYBRID) {
                ss.setMlGateEnabled(true);
            }
            strategySettingsService.save(ss);
            applied = true;
        } catch (Exception e) {
            log.warn("🧠 TRAIN apply settings failed chatId={} type={} err={}", chatId, type, e.toString(), e);
        }

        publishSettingsUpdated(chatId, type, "ml_train:" + reasonNorm);
        lastTrainAt.put(cooldownKey, now);

        log.info("🧠 TRAIN DONE type={} ex={} net={} sym={} tf={} applied={} modelKey={} ver={} schemaHash={} rows={}",
                type, exchange, network, symbol, timeframe, applied, responseModelKey, responseModelVersion, finalSchemaHash, rows.size());

        return new MlTrainingResult(true, applied, responseModelKey, responseModelVersion, finalSchemaHash, null);
    }



    /**
     * Первый запуск: обучаемся не по ml_samples, а прямо по выбранным свечам.
     * Используется только для WINDOW_SCALPING.
     */
    public MlTrainingResult trainOnSelectedCandles(Long chatId,
                                                   StrategyType type,
                                                   String exchangeOverride,
                                                   NetworkType networkOverride,
                                                   String symbolOverride,
                                                   String timeframeOverride,
                                                   Integer candlesLimitOverride,
                                                   String reason) {
        if (type != StrategyType.WINDOW_SCALPING && type != StrategyType.FIBONACCI_GRID) {
            return trainNow(chatId, type, reason);
        }
        if (props == null || !props.isEnabled()) {
            log.warn("🧠 TRAIN SKIP: training disabled");
            return new MlTrainingResult(false, false, null, null, null, "training_disabled");
        }
        if (chatId == null || chatId <= 0 || type == null) {
            log.warn("🧠 TRAIN SKIP: bad args chatId={} type={}", chatId, type);
            return new MlTrainingResult(false, false, null, null, null, "bad_args");
        }

        MlClient mlClient = mlClientProvider != null ? mlClientProvider.getIfAvailable() : null;
        if (mlClient == null) {
            log.warn("🧠 TRAIN SKIP: MlClient missing");
            return new MlTrainingResult(false, false, null, null, null, "ml_client_missing");
        }

        StrategySettings ss;
        try {
            ss = strategySettingsService.getSettings(chatId, type);
            if (ss == null) {
                ss = strategySettingsService.getOrCreate(chatId, type);
            }
        } catch (Exception e) {
            log.warn("🧠 TRAIN settings load failed chatId={} type={} err={}", chatId, type, e.toString());
            return new MlTrainingResult(false, false, null, null, null, "strategy_settings_missing");
        }
        if (ss == null) {
            return new MlTrainingResult(false, false, null, null, null, "strategy_settings_missing");
        }

        String reasonNorm = normTrim(reason);
        if (reasonNorm == null) reasonNorm = "prepare_start_train";

        String symbol = normUpper(symbolOverride);
        if (symbol == null) symbol = normUpper(ss.getSymbol());

        String timeframe = normLower(timeframeOverride);
        if (timeframe == null) timeframe = normLower(ss.getTimeframe());

        String exchange = normUpper(exchangeOverride);
        if (exchange == null) exchange = normUpper(stringOf(ss.getExchangeName()));

        NetworkType network = networkOverride != null ? networkOverride : ss.getNetworkType();
        String networkName = normUpper(network != null ? network.name() : stringOf(ss.getNetworkType()));

        if (symbol == null) return new MlTrainingResult(false, false, null, null, null, "symbol_missing");
        if (timeframe == null) return new MlTrainingResult(false, false, null, null, null, "timeframe_missing");
        if (exchange == null) return new MlTrainingResult(false, false, null, null, null, "exchange_missing");
        if (networkName == null) return new MlTrainingResult(false, false, null, null, null, "network_missing");

        String modelKey = MlGateway.buildContextModelKey(type, exchange, networkName, symbol, timeframe);

        Instant now = Instant.now();
        boolean bypassCooldown = reasonNorm.toLowerCase(Locale.ROOT).contains("prepare_start");
        String cooldownKey = cooldownKey(modelKey);
        Instant last = lastTrainAt.get(cooldownKey);
        long cooldownMinutes = Math.max(0, props.getCooldownMinutes());
        if (!bypassCooldown && last != null && cooldownMinutes > 0) {
            long passed = ChronoUnit.MINUTES.between(last, now);
            if (passed < cooldownMinutes) {
                log.info("🧠 TRAIN SKIP: cooldown modelKey={} passed={}m need={}m", modelKey, passed, cooldownMinutes);
                return new MlTrainingResult(false, false, modelKey, null, normTrim(ss.getMlSchemaHash()), "cooldown");
            }
        }

        int candlesLimit = candlesLimitOverride != null && candlesLimitOverride > 0
                ? candlesLimitOverride
                : (ss.getCachedCandlesLimit() != null && ss.getCachedCandlesLimit() > 0 ? ss.getCachedCandlesLimit() : Math.max(300, props.getRowsLimit() * 3));

        List<Object> candles = loadSelectedCandles(chatId, type, exchange, network, symbol, timeframe, candlesLimit);
        if (candles.isEmpty()) {
            log.warn("🧠 TRAIN SKIP: no selected candles type={} ex={} net={} sym={} tf={} limit={}",
                    type, exchange, networkName, symbol, timeframe, candlesLimit);
            return new MlTrainingResult(false, false, modelKey, null, normTrim(ss.getMlSchemaHash()), "no_selected_candles");
        }

        List<String> featureSchema;
        List<Map<String, Object>> rows;
        Map<String, Object> params = new LinkedHashMap<>();

        if (type == StrategyType.FIBONACCI_GRID) {
            FiboTrainingConfig cfg = resolveFiboTrainingConfig(chatId);
            featureSchema = List.of(
                    "gridLevels",
                    "distancePct",
                    "takeProfitPct",
                    "stopLossPct",
                    "levelHitIndex",
                    "levelDistancePct",
                    "anchorPrice",
                    "price",
                    "rangePct",
                    "ret1Pct",
                    "ret3Pct",
                    "ret5Pct",
                    "volatilityPct",
                    "drawdownFromAnchorPct",
                    "bounceFromLowPct",
                    "positionInRange01"
            );
            rows = buildFiboRowsFromCandles(candles, cfg, candlesLimit, chatId, symbol, exchange, networkName, timeframe);
            params.put("datasetSource", "selected_candles");
            params.put("gridLevels", cfg.gridLevels);
            params.put("distancePct", cfg.distancePct);
            params.put("tpPct", cfg.takeProfitPct);
            params.put("slPct", cfg.stopLossPct);
        } else {
            WindowTrainingConfig cfg = resolveWindowTrainingConfig(chatId, exchange, network, symbol, timeframe);
            featureSchema = List.of(
                    "windowSize",
                    "lastPrice",
                    "price",
                    "low",
                    "high",
                    "range",
                    "rangePct",
                    "volatilityPct",
                    "pos01",
                    "posPct",
                    "lowZone01",
                    "highZone01",
                    "diffPctForEntry",
                    "retWindowPct",
                    "momentum1",
                    "smaFastRel",
                    "smaSlowRel"
            );
            rows = buildWindowRowsFromCandles(candles, cfg, candlesLimit, chatId, symbol, exchange, networkName, timeframe);
            params.put("datasetSource", "selected_candles");
            params.put("windowSize", cfg.windowSize);
            params.put("tpPct", cfg.takeProfitPct);
            params.put("slPct", cfg.stopLossPct);
        }

        String computedSchemaHash = computeSchemaHash(featureSchema);
        if (rows.size() < props.getMinSamples()) {
            log.warn("🧠 TRAIN SKIP: not enough candle rows modelKey={} rows={} minSamples={} schemaHash={} candles={} type={}",
                    modelKey, rows.size(), props.getMinSamples(), computedSchemaHash, candles.size(), type);
            return new MlTrainingResult(false, false, modelKey, null, computedSchemaHash, "not_enough_candle_rows=" + rows.size());
        }

        boolean settingsChangedBeforeTrain = false;
        if (!Objects.equals(normTrim(ss.getMlModelKey()), modelKey)) {
            ss.setMlModelKey(modelKey);
            settingsChangedBeforeTrain = true;
        }
        if (!Objects.equals(normTrim(ss.getMlSchemaHash()), computedSchemaHash)) {
            ss.setMlSchemaHash(computedSchemaHash);
            settingsChangedBeforeTrain = true;
        }
        if (settingsChangedBeforeTrain) {
            try {
                ss = strategySettingsService.save(ss);
            } catch (Exception e) {
                log.warn("🧠 TRAIN pre-save settings failed chatId={} type={} err={}", chatId, type, e.toString());
            }
        }

        MlTrainRequest req = new MlTrainRequest();
        req.setChatId(chatId);
        req.setStrategyType(type.name());
        req.setSymbol(symbol);
        req.setTimeframe(timeframe);
        req.setModelKey(modelKey);
        req.setSchemaHash(computedSchemaHash);
        req.setFeatureSchema(featureSchema);
        req.setRows(rows);

        params.put("reason", reasonNorm);
        params.put("rows", rows.size());
        params.put("candles", candles.size());
        params.put("modelKey", modelKey);
        params.put("schemaHash", computedSchemaHash);
        params.put("exchange", exchange);
        params.put("network", networkName);
        req.setParams(params);

        log.info("🧠 TRAIN START type={} ex={} net={} sym={} tf={} rows={} candles={} schemaSize={} schemaHash={} modelKey={} reason={} source=selected_candles",
                type, exchange, networkName, symbol, timeframe, rows.size(), candles.size(), featureSchema.size(), computedSchemaHash, modelKey, reasonNorm);

        MlTrainResponse resp;
        try {
            resp = mlClient.train(req);
        } catch (Exception e) {
            log.warn("🧠 TRAIN exception type={} ex={} net={} sym={} tf={} err={}",
                    type, exchange, networkName, symbol, timeframe, e.toString(), e);
            return new MlTrainingResult(false, false, modelKey, null, computedSchemaHash, "train_exception");
        }

        if (resp == null) {
            return new MlTrainingResult(false, false, modelKey, null, computedSchemaHash, "train_null");
        }
        if (!resp.isOk()) {
            String error = normTrim(resp.getError()) != null ? resp.getError() : "train_not_ok";
            return new MlTrainingResult(false, false, modelKey, null, computedSchemaHash, error);
        }

        String responseModelKey = modelKey;
        String responseModelVersion = normTrim(resp.getModelVersion());
        String responseSchemaHash = normTrim(resp.getSchemaHash());
        String finalSchemaHash = responseSchemaHash != null ? responseSchemaHash : computedSchemaHash;

        saveArtifactSafe(chatId, type, symbol, timeframe, responseModelKey, responseModelVersion, finalSchemaHash, resp.getMetricsJson(), now);

        boolean applied = false;
        try {
            AdvancedControlMode mode = ss.getAdvancedControlMode() != null ? ss.getAdvancedControlMode() : AdvancedControlMode.MANUAL;
            ss.setMlModelKey(responseModelKey);
            ss.setMlModelVersion(responseModelVersion);
            ss.setMlSchemaHash(finalSchemaHash);
            BigDecimal minProb = ss.getGateMinProb();
            if ((minProb == null || minProb.signum() <= 0) && props.getThresholdAutoEnable() > 0) {
                ss.setGateMinProb(BigDecimal.valueOf(props.getThresholdAutoEnable()).setScale(6, RoundingMode.HALF_UP));
            }
            if (mode == AdvancedControlMode.AI || mode == AdvancedControlMode.HYBRID) {
                ss.setMlGateEnabled(true);
            }
            strategySettingsService.save(ss);
            applied = true;
        } catch (Exception e) {
            log.warn("🧠 TRAIN apply settings failed chatId={} type={} err={}", chatId, type, e.toString(), e);
        }

        publishSettingsUpdated(chatId, type, "ml_train:" + reasonNorm);
        lastTrainAt.put(cooldownKey, now);

        log.info("🧠 TRAIN DONE type={} ex={} net={} sym={} tf={} applied={} modelKey={} ver={} schemaHash={} rows={} source=selected_candles",
                type, exchange, networkName, symbol, timeframe, applied, responseModelKey, responseModelVersion, finalSchemaHash, rows.size());

        return new MlTrainingResult(true, applied, responseModelKey, responseModelVersion, finalSchemaHash, null);
    }

    private List<Object> loadSelectedCandles(Long chatId,
                                             StrategyType type,
                                             String exchange,
                                             NetworkType network,
                                             String symbol,
                                             String timeframe,
                                             int candlesLimit) {
        tryWarmupHistory(chatId, type, exchange, network, symbol, timeframe, candlesLimit);

        Object service = getBeanByName("marketDataStreamService");
        if (service == null) {
            service = getBeanByName("marketStreamService");
        }

        List<Object> cached = normalizeCandleResult(
                tryInvokeCandleLoader(service, chatId, type, exchange, network, symbol, timeframe, candlesLimit),
                candlesLimit
        );
        if (!cached.isEmpty()) {
            return cached;
        }

        List<Object> direct = loadCandlesDirectFromExchange(exchange, network, symbol, timeframe, candlesLimit);
        if (!direct.isEmpty()) {
            backfillRuntimeCandleCache(chatId, type, exchange, network, symbol, timeframe, direct);
            return direct;
        }

        return List.of();
    }

    private List<Object> loadCandlesDirectFromExchange(String exchange,
                                                       NetworkType network,
                                                       String symbol,
                                                       String timeframe,
                                                       int candlesLimit) {
        Object bean = getBeanByName("exchangeClientFactory");
        if (!(bean instanceof ExchangeClientFactory factory)) {
            return List.of();
        }

        try {
            ExchangeClient client = factory.get(exchange, network);
            if (client == null) {
                return List.of();
            }

            Object raw;
            try {
                raw = client.getKlines(symbol, timeframe, candlesLimit);
            } catch (Exception first) {
                long end = System.currentTimeMillis();
                raw = client.getKlines(symbol, timeframe, end - Math.max(1L, candlesLimit) * 60_000L, end, candlesLimit);
            }

            List<Object> normalized = normalizeCandleResult(raw, candlesLimit);
            if (!normalized.isEmpty()) {
                log.info("🧠 TRAIN candle fallback: loaded from exchange ex={} net={} sym={} tf={} candles={}",
                        exchange, network, symbol, timeframe, normalized.size());
            }
            return normalized;
        } catch (Exception e) {
            log.warn("🧠 TRAIN candle fallback failed ex={} net={} sym={} tf={} err={}",
                    exchange, network, symbol, timeframe, e.toString());
            return List.of();
        }
    }

    private void backfillRuntimeCandleCache(Long chatId,
                                            StrategyType type,
                                            String exchange,
                                            NetworkType network,
                                            String symbol,
                                            String timeframe,
                                            List<Object> rawCandles) {
        Object bean = getBeanByName("marketDataStreamService");
        if (!(bean instanceof MarketDataStreamService streamService)) {
            return;
        }

        List<Candle> candles = toMarketCandles(rawCandles);
        if (candles.isEmpty()) {
            return;
        }

        try {
            streamService.putCandles(chatId, type, exchange, network, symbol, timeframe, candles);
        } catch (Exception e) {
            log.debug("🧠 TRAIN runtime cache backfill skipped chatId={} type={} ex={} net={} sym={} tf={} err={}",
                    chatId, type, exchange, network, symbol, timeframe, e.toString());
        }
    }

    private List<Candle> toMarketCandles(List<Object> rawCandles) {
        if (rawCandles == null || rawCandles.isEmpty()) {
            return List.of();
        }

        List<Candle> out = new ArrayList<>(rawCandles.size());
        for (Object raw : rawCandles) {
            CandlePoint point = toCandlePoint(raw);
            if (point == null) {
                continue;
            }
            out.add(new Candle(
                    point.openTimeMs(),
                    point.open(),
                    point.high(),
                    point.low(),
                    point.close(),
                    0.0d,
                    true
            ));
        }
        return out;
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
                new Object[]{chatId, type, exchange, network, symbol, timeframe, Integer.valueOf(candlesLimit)},
                new Object[]{chatId, type, symbol, timeframe, exchange, network, candlesLimit},
                new Object[]{chatId, type, symbol, timeframe, candlesLimit, exchange, network},
                new Object[]{chatId, type, symbol, timeframe, candlesLimit},
                new Object[]{chatId, type, exchange, network, symbol, timeframe},
                new Object[]{chatId, type, symbol, timeframe},
                new Object[]{chatId, symbol, timeframe, candlesLimit},
                new Object[]{symbol, timeframe, candlesLimit}
        );

        List<String> methodNames = List.of(
                "getCachedCandles",
                "getRecentCandles",
                "getCandles",
                "loadCandles",
                "getCandlesSnapshot",
                "snapshotCandles",
                "findCandles",
                "readCandles"
        );

        for (String methodName : methodNames) {
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

        boolean invoked = false;
        for (String methodName : List.of("warmup", "ensureWarmup", "warmupIfNeeded", "ensureHistory")) {
            for (Object[] args : candidates) {
                try {
                    Object ignored = invokeCompatible(warmupService, methodName, args);
                    if (ignored != null) {
                        invoked = true;
                    } else if (hasCompatibleMethod(warmupService, methodName, args)) {
                        // Метод может быть void — всё равно считаем попытку валидной и пробуем остальные сигнатуры без раннего выхода.
                        invoked = true;
                    }
                } catch (Exception ignore) {
                    // идём дальше по другим сигнатурам
                }
            }
        }

        if (!invoked) {
            log.debug("🧠 TRAIN warmup skipped: no compatible warmup method type={} ex={} net={} sym={} tf={} limit={}",
                    type, exchange, network, symbol, timeframe, candlesLimit);
        }
    }

    private WindowTrainingConfig resolveWindowTrainingConfig(Long chatId,
                                                            String exchange,
                                                            NetworkType network,
                                                            String symbol,
                                                            String timeframe) {
        int windowSize = 14;
        double entryFromLowPct = 35.0;
        double entryFromHighPct = 35.0;
        double takeProfitPct = 0.35;
        double stopLossPct = 0.18;
        double minRangePct = 0.05;

        try {
            Object svc = getBeanByName("windowScalpingStrategySettingsService");
            if (svc != null) {
                Object cfg = invokeCompatible(
                        svc,
                        "getOrCreate",
                        chatId,
                        exchange,
                        network,
                        symbol,
                        timeframe
                );
                if (cfg == null) {
                    cfg = invokeCompatible(svc, "getOrCreate", chatId);
                }
                if (cfg != null) {
                    Integer w = toInt(readBeanValue(cfg, "getWindowSize", "windowSize"));
                    Double low = toDouble(readBeanValue(cfg, "getEntryFromLowPct", "entryFromLowPct"));
                    Double high = toDouble(readBeanValue(cfg, "getEntryFromHighPct", "entryFromHighPct"));
                    Double tp = toDouble(readBeanValue(cfg, "getTakeProfitPct", "takeProfitPct"));
                    Double sl = toDouble(readBeanValue(cfg, "getStopLossPct", "stopLossPct"));
                    Double mr = toDouble(readBeanValue(cfg, "getMinRangePct", "minRangePct"));

                    if (w != null && w >= 5) windowSize = w;
                    if (low != null && low > 0) entryFromLowPct = low;
                    if (high != null && high > 0) entryFromHighPct = high;
                    if (tp != null && tp > 0) takeProfitPct = tp;
                    if (sl != null && sl > 0) stopLossPct = sl;
                    if (mr != null && mr > 0) minRangePct = mr;
                }
            }
        } catch (Exception e) {
            log.debug("🧠 TRAIN candle-config fallback chatId={} ex={} net={} sym={} tf={} err={}",
                    chatId, exchange, network, symbol, timeframe, e.toString());
        }

        return new WindowTrainingConfig(
                Math.max(5, windowSize),
                clampPct01(entryFromLowPct / 100.0d),
                clampPct01(1.0d - (entryFromHighPct / 100.0d)),
                takeProfitPct,
                stopLossPct,
                minRangePct
        );
    }

    private List<Map<String, Object>> buildWindowRowsFromCandles(List<Object> candles,
                                                                 WindowTrainingConfig cfg,
                                                                 int candlesLimit,
                                                                 Long chatId,
                                                                 String symbol,
                                                                 String exchange,
                                                                 String network,
                                                                 String timeframe) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (candles == null || candles.isEmpty() || cfg == null) return rows;

        List<CandlePoint> points = new ArrayList<>(candles.size());
        for (Object candle : candles) {
            CandlePoint p = toCandlePoint(candle);
            if (p != null) points.add(p);
        }
        points.sort(Comparator.comparingLong(CandlePoint::openTimeMs));

        int windowSize = Math.max(5, cfg.windowSize);
        int horizon = Math.max(8, Math.min(48, windowSize * 3));

        for (int i = windowSize - 1; i < points.size() - 2; i++) {
            List<CandlePoint> window = points.subList(i - windowSize + 1, i + 1);

            double low = window.stream().mapToDouble(CandlePoint::low).min().orElse(Double.NaN);
            double high = window.stream().mapToDouble(CandlePoint::high).max().orElse(Double.NaN);
            double lastPrice = points.get(i).close();

            if (!Double.isFinite(low) || !Double.isFinite(high) || !Double.isFinite(lastPrice) || low <= 0.0 || high <= low) {
                continue;
            }

            double range = high - low;
            double rangePct = (range / low) * 100.0d;
            if (!Double.isFinite(rangePct) || rangePct <= 0.0 || rangePct + 1e-12 < cfg.minRangePct) {
                continue;
            }

            double clampedPrice = Math.max(low, Math.min(high, lastPrice));
            double pos01 = (clampedPrice - low) / range;
            if (!Double.isFinite(pos01)) {
                continue;
            }

            if (pos01 > cfg.lowZone01) {
                continue;
            }

            double diffPctForEntry = Math.max(0.000001d, (cfg.lowZone01 - pos01) * 100.0d);
            double retWindowPct = calcWindowReturnPct(window);
            double momentum1 = calcMomentum1Pct(window);
            double volatilityPct = calcVolatilityPct(window);
            double smaFast = calcSma(window, Math.max(3, Math.min(5, windowSize)));
            double smaSlow = calcSma(window, Math.max(5, Math.min(10, windowSize)));

            Integer label = simulateTpSlLabel(points, i, horizon, cfg.takeProfitPct, cfg.stopLossPct);
            if (label == null) {
                continue;
            }

            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("windowSize", windowSize);
            row.put("lastPrice", lastPrice);
            row.put("price", lastPrice);
            row.put("low", low);
            row.put("high", high);
            row.put("range", range);
            row.put("rangePct", rangePct);
            row.put("volatilityPct", volatilityPct);
            row.put("pos01", pos01);
            row.put("posPct", pos01 * 100.0d);
            row.put("lowZone01", cfg.lowZone01);
            row.put("highZone01", cfg.highZone01);
            row.put("diffPctForEntry", diffPctForEntry);
            row.put("retWindowPct", retWindowPct);
            row.put("momentum1", momentum1);
            row.put("smaFastRel", relPct(lastPrice, smaFast));
            row.put("smaSlowRel", relPct(lastPrice, smaSlow));
            row.put("label", label);
            row.put("chatId", chatId);
            row.put("strategyType", StrategyType.WINDOW_SCALPING.name());
            row.put("symbol", symbol);
            row.put("exchange", exchange);
            row.put("network", network);
            row.put("timeframe", timeframe);
            row.put("ts", points.get(i).closeTimeMs());

            rows.add(row);
        }

        int rowsLimit = Math.max(100, props.getRowsLimit());
        if (rows.size() > rowsLimit) {
            rows = new ArrayList<>(rows.subList(rows.size() - rowsLimit, rows.size()));
        }
        return rows;
    }

    private Integer simulateTpSlLabel(List<CandlePoint> points,
                                      int entryIndex,
                                      int horizon,
                                      double tpPct,
                                      double slPct) {
        if (points == null || entryIndex < 0 || entryIndex >= points.size()) return null;

        double entry = points.get(entryIndex).close();
        if (!Double.isFinite(entry) || entry <= 0.0d) return null;

        double tp = entry * (1.0d + Math.max(0.0001d, tpPct) / 100.0d);
        double sl = entry * (1.0d - Math.max(0.0001d, slPct) / 100.0d);

        int end = Math.min(points.size() - 1, entryIndex + Math.max(2, horizon));
        for (int i = entryIndex + 1; i <= end; i++) {
            CandlePoint p = points.get(i);
            if (p.low() <= sl) return 0;
            if (p.high() >= tp) return 1;
        }

        double finalClose = points.get(end).close();
        if (!Double.isFinite(finalClose)) return null;
        return finalClose >= entry ? 1 : 0;
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

    private double calcWindowReturnPct(List<CandlePoint> window) {
        if (window == null || window.isEmpty()) return 0.0d;
        double first = window.get(0).close();
        double last = window.get(window.size() - 1).close();
        if (!Double.isFinite(first) || !Double.isFinite(last) || first <= 0.0d) return 0.0d;
        return ((last - first) / first) * 100.0d;
    }

    private double calcMomentum1Pct(List<CandlePoint> window) {
        if (window == null || window.size() < 2) return 0.0d;
        double prev = window.get(window.size() - 2).close();
        double last = window.get(window.size() - 1).close();
        if (!Double.isFinite(prev) || !Double.isFinite(last) || prev <= 0.0d) return 0.0d;
        return ((last - prev) / prev) * 100.0d;
    }

    private double calcVolatilityPct(List<CandlePoint> window) {
        if (window == null || window.size() < 3) return 0.0d;
        double mean = 0.0d;
        int count = 0;
        for (CandlePoint p : window) {
            if (p == null || !Double.isFinite(p.close()) || p.close() <= 0.0d) continue;
            mean += p.close();
            count++;
        }
        if (count < 3) return 0.0d;
        mean /= count;
        if (!Double.isFinite(mean) || mean <= 0.0d) return 0.0d;

        double var = 0.0d;
        for (CandlePoint p : window) {
            if (p == null || !Double.isFinite(p.close()) || p.close() <= 0.0d) continue;
            double d = p.close() - mean;
            var += d * d;
        }
        var /= count;

        double std = Math.sqrt(var);
        if (!Double.isFinite(std)) return 0.0d;
        return (std / mean) * 100.0d;
    }

    private double calcSma(List<CandlePoint> window, int period) {
        if (window == null || window.isEmpty() || period <= 0) return 0.0d;
        int from = Math.max(0, window.size() - period);
        double sum = 0.0d;
        int count = 0;
        for (int i = from; i < window.size(); i++) {
            CandlePoint p = window.get(i);
            if (p == null || !Double.isFinite(p.close()) || p.close() <= 0.0d) continue;
            sum += p.close();
            count++;
        }
        return count <= 0 ? 0.0d : sum / count;
    }

    private double relPct(double price, double avg) {
        if (!Double.isFinite(price) || !Double.isFinite(avg) || avg <= 0.0d) return 0.0d;
        return ((price - avg) / avg) * 100.0d;
    }

    private double clampPct01(double v) {
        if (!Double.isFinite(v)) return 0.0d;
        if (v < 0.0d) return 0.0d;
        if (v > 1.0d) return 1.0d;
        return v;
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

    private boolean hasCompatibleMethod(Object target, String methodName, Object[] args) {
        if (target == null || methodName == null) return false;
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != (args != null ? args.length : 0)) continue;
            if (areArgumentsCompatible(pt, args)) return true;
        }
        return false;
    }

    private Object invokeCompatible(Object target, String methodName, Object... args) {
        if (target == null || methodName == null) return null;
        for (Method m : target.getClass().getMethods()) {
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
            if ((t == Long.class || t == Integer.class || t == Double.class || t == Float.class) && a instanceof String) continue;
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
                @SuppressWarnings({"rawtypes","unchecked"})
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

    private Object readBeanValue(Object target, String... methodNames) {
        if (target == null || methodNames == null) return null;
        for (String methodName : methodNames) {
            if (methodName == null || methodName.isBlank()) continue;
            try {
                Method m = target.getClass().getMethod(methodName);
                return m.invoke(target);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Integer toInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd.doubleValue();
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }


    private FiboTrainingConfig resolveFiboTrainingConfig(Long chatId) {
        int gridLevels = 6;
        double distancePct = 0.50d;
        double takeProfitPct = 0.80d;
        double stopLossPct = 1.20d;

        try {
            Object svc = getBeanByName("fibonacciGridStrategySettingsService");
            if (svc == null) {
                svc = getBeanByName("fibonacciGridStrategySettingsServiceImpl");
            }
            if (svc != null) {
                Object cfg = invokeCompatible(svc, "getOrCreate", chatId);
                if (cfg != null) {
                    Integer levels = toInt(readBeanValue(cfg, "getGridLevels", "gridLevels"));
                    Double distance = toDouble(readBeanValue(cfg, "getDistancePct", "distancePct"));
                    Double tp = toDouble(readBeanValue(cfg, "getTakeProfitPct", "takeProfitPct"));
                    Double sl = toDouble(readBeanValue(cfg, "getStopLossPct", "stopLossPct"));

                    if (levels != null && levels > 0) gridLevels = levels;
                    if (distance != null && distance > 0) distancePct = distance;
                    if (tp != null && tp > 0) takeProfitPct = tp;
                    if (sl != null && sl > 0) stopLossPct = sl;
                }
            }
        } catch (Exception e) {
            log.debug("🧠 TRAIN fibo-config fallback chatId={} err={}", chatId, e.toString());
        }

        return new FiboTrainingConfig(
                Math.max(1, gridLevels),
                Math.max(0.05d, distancePct),
                Math.max(0.05d, takeProfitPct),
                Math.max(0.05d, stopLossPct)
        );
    }


private List<Map<String, Object>> buildFiboRowsFromCandles(List<Object> candles,
                                                           FiboTrainingConfig cfg,
                                                           int candlesLimit,
                                                           Long chatId,
                                                           String symbol,
                                                           String exchange,
                                                           String network,
                                                           String timeframe) {
    List<Map<String, Object>> rows = new ArrayList<>();
    if (candles == null || candles.isEmpty() || cfg == null) return rows;

    List<CandlePoint> points = new ArrayList<>(candles.size());
    for (Object candle : candles) {
        CandlePoint p = toCandlePoint(candle);
        if (p != null) points.add(p);
    }
    points.sort(Comparator.comparingLong(CandlePoint::openTimeMs));

    int minWarmup = Math.max(20, cfg.gridLevels + 8);
    if (points.size() < minWarmup + 8) {
        return rows;
    }

    int anchorWindow = Math.max(12, Math.min(60, cfg.gridLevels * 5));
    int horizon = Math.max(8, Math.min(64, cfg.gridLevels * 6));
    double gridStepPct = Math.max(0.05d, cfg.distancePct);

    for (int i = anchorWindow; i < points.size() - Math.max(3, Math.min(6, horizon / 2)); i++) {
        CandlePoint point = points.get(i);
        List<CandlePoint> anchorSlice = points.subList(i - anchorWindow, i);

        double anchor = anchorSlice.stream().mapToDouble(CandlePoint::high).max().orElse(Double.NaN);
        double rollingLow = anchorSlice.stream().mapToDouble(CandlePoint::low).min().orElse(Double.NaN);
        double price = point.close();

        if (!Double.isFinite(anchor) || !Double.isFinite(price) || !Double.isFinite(rollingLow)
                || anchor <= 0.0d || rollingLow <= 0.0d) {
            continue;
        }

        double rawRange = anchor - rollingLow;
        if (!Double.isFinite(rawRange) || rawRange <= 0.0d) {
            continue;
        }

        double rangePct = ((anchor - rollingLow) / rollingLow) * 100.0d;
        if (!Double.isFinite(rangePct) || rangePct <= 0.0d) {
            continue;
        }

        double drawdownFromAnchorPct = Math.max(0.0d, ((anchor - price) / anchor) * 100.0d);
        double bounceFromLowPct = Math.max(0.0d, ((price - rollingLow) / rollingLow) * 100.0d);
        double positionInRange01 = clampPct01((price - rollingLow) / rawRange);

        int derivedLevelIdx = (int) Math.floor(drawdownFromAnchorPct / gridStepPct);
        if (derivedLevelIdx < 0) derivedLevelIdx = 0;
        if (derivedLevelIdx >= cfg.gridLevels) derivedLevelIdx = cfg.gridLevels - 1;

        double levelHitIndex = derivedLevelIdx;
        double levelDistancePct = Math.min(drawdownFromAnchorPct, gridStepPct * Math.max(1, cfg.gridLevels));

        Integer label = simulateTpSlLabel(points, i, horizon, cfg.takeProfitPct, cfg.stopLossPct);
        if (label == null) {
            continue;
        }

        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("gridLevels", cfg.gridLevels);
        row.put("distancePct", cfg.distancePct);
        row.put("takeProfitPct", cfg.takeProfitPct);
        row.put("stopLossPct", cfg.stopLossPct);
        row.put("levelHitIndex", levelHitIndex);
        row.put("levelDistancePct", levelDistancePct);
        row.put("anchorPrice", anchor);
        row.put("price", price);
        row.put("rangePct", rangePct);
        row.put("ret1Pct", calcCloseReturnPct(points, i, 1));
        row.put("ret3Pct", calcCloseReturnPct(points, i, 3));
        row.put("ret5Pct", calcCloseReturnPct(points, i, 5));
        row.put("volatilityPct", calcVolatilityPct(anchorSlice));
        row.put("drawdownFromAnchorPct", drawdownFromAnchorPct);
        row.put("bounceFromLowPct", bounceFromLowPct);
        row.put("positionInRange01", positionInRange01);
        row.put("label", label);
        row.put("chatId", chatId);
        row.put("strategyType", StrategyType.FIBONACCI_GRID.name());
        row.put("symbol", symbol);
        row.put("exchange", exchange);
        row.put("network", network);
        row.put("timeframe", timeframe);
        row.put("ts", point.closeTimeMs());
        rows.add(row);
    }

    int rowsLimit = Math.max(100, props.getRowsLimit());
    if (rows.size() > rowsLimit) {
        rows = new ArrayList<>(rows.subList(rows.size() - rowsLimit, rows.size()));
    }

    log.info("🧠 TRAIN FIBO rows built chatId={} sym={} ex={} net={} tf={} candles={} rows={} levels={} stepPct={} horizon={}",
            chatId,
            symbol,
            exchange,
            network,
            timeframe,
            points.size(),
            rows.size(),
            cfg.gridLevels,
            cfg.distancePct,
            horizon);

    return rows;
}

private double calcCloseReturnPct(List<CandlePoint> points, int index, int back) {
        if (points == null || index < 0 || back <= 0 || index - back < 0 || index >= points.size()) return 0.0d;
        double prev = points.get(index - back).close();
        double cur = points.get(index).close();
        if (!Double.isFinite(prev) || !Double.isFinite(cur) || prev <= 0.0d) return 0.0d;
        return ((cur - prev) / prev) * 100.0d;
    }
    private record FiboTrainingConfig(int gridLevels,
                                      double distancePct,
                                      double takeProfitPct,
                                      double stopLossPct) {}

    private record WindowTrainingConfig(int windowSize,
                                        double lowZone01,
                                        double highZone01,
                                        double takeProfitPct,
                                        double stopLossPct,
                                        double minRangePct) {}

    private record CandlePoint(double open,
                               double high,
                               double low,
                               double close,
                               long openTimeMs,
                               long closeTimeMs) {}
    private List<MlSampleEntity> safeFindRecent(Long chatId, StrategyType type, Instant from) {
        try {
            List<MlSampleEntity> r = sampleRepo.findRecent(chatId, type, from);
            return r != null ? r : List.of();
        } catch (Exception e) {
            log.warn("🧠 TRAIN samples load failed chatId={} type={} err={}", chatId, type, e.toString());
            return List.of();
        }
    }

    private List<MlSampleEntity> safeFindForTrainingByContext(StrategyType type,
                                                              String symbol,
                                                              String timeframe,
                                                              String exchange,
                                                              String network,
                                                              Instant from) {
        try {
            List<MlSampleEntity> r = sampleRepo.findForTrainingByContext(type, symbol, timeframe, exchange, network, from);
            return r != null ? r : List.of();
        } catch (Exception e) {
            log.warn("🧠 TRAIN context samples load failed type={} ex={} net={} sym={} tf={} err={}",
                    type, exchange, network, symbol, timeframe, e.toString());
            return List.of();
        }
    }

    private Context inferContextFromRecentSamples(Long chatId, StrategyType type, Instant from) {
        List<MlSampleEntity> recent = safeFindRecent(chatId, type, from);
        recent.sort(Comparator.comparing(MlTrainingServiceImpl::sampleTimeMs, Comparator.nullsLast(Comparator.reverseOrder())));
        for (MlSampleEntity s : recent) {
            if (s == null || s.getFeaturesJson() == null || !s.getFeaturesJson().isObject()) continue;
            if (labelToIntOrNull(s.getLabel()) == null) continue;
            String symbol = normUpper(s.getSymbol());
            String timeframe = normLower(s.getTimeframe());
            String exchange = normUpper(s.getExchange());
            String network = normUpper(s.getNetwork());
            if (symbol != null || timeframe != null || exchange != null || network != null) {
                return new Context(symbol, timeframe, exchange, network);
            }
        }
        return new Context(null, null, null, null);
    }

    private boolean matchesContext(MlSampleEntity sample,
                                   StrategyType type,
                                   String symbol,
                                   String timeframe,
                                   String exchange,
                                   String network) {
        if (sample == null) return false;
        if (sample.getStrategyType() != type) return false;
        if (!Objects.equals(normUpper(sample.getSymbol()), normUpper(symbol))) return false;
        if (!Objects.equals(normLower(sample.getTimeframe()), normLower(timeframe))) return false;
        if (normUpper(exchange) != null && !Objects.equals(normUpper(sample.getExchange()), normUpper(exchange))) return false;
        if (normUpper(network) != null && !Objects.equals(normUpper(sample.getNetwork()), normUpper(network))) return false;
        return true;
    }

    private String chooseSchemaHash(String preferredSchemaHash, Map<String, Integer> schemaCounts) {
        if (preferredSchemaHash != null && schemaCounts.containsKey(preferredSchemaHash)) {
            return preferredSchemaHash;
        }
        return schemaCounts.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private int estimateDistinctUsers(List<MlSampleEntity> samples) {
        Set<Long> ids = new HashSet<>();
        for (MlSampleEntity s : samples) {
            if (s != null && s.getChatId() != null) ids.add(s.getChatId());
        }
        return ids.size();
    }

    private List<Map<String, Object>> toRows(List<MlSampleEntity> samples, List<String> featureSchema) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (samples == null || samples.isEmpty() || featureSchema == null || featureSchema.isEmpty()) return rows;
        for (MlSampleEntity s : samples) {
            if (s == null || s.getFeaturesJson() == null || !s.getFeaturesJson().isObject()) continue;
            Integer y = labelToIntOrNull(s.getLabel());
            if (y == null) continue;
            Map<String, Object> features = resolveTrainingFeatureMap(s);
            if (features.isEmpty()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            boolean hasAll = true;
            for (String key : featureSchema) {
                if (!features.containsKey(key)) {
                    hasAll = false;
                    break;
                }
                row.put(key, features.get(key));
            }
            if (!hasAll) continue;
            row.put("y", y);
            if (s.getTs() != null) row.put("tsMs", s.getTs().toEpochMilli());
            else if (s.getCreatedAt() != null) row.put("tsMs", s.getCreatedAt().toEpochMilli());
            rows.add(row);
        }
        return rows;
    }

    private void saveArtifactSafe(Long chatId,
                                  StrategyType type,
                                  String symbol,
                                  String timeframe,
                                  String modelKey,
                                  String modelVersion,
                                  String schemaHash,
                                  String metricsJson,
                                  Instant createdAt) {
        try {
            MlModelArtifactEntity art = MlModelArtifactEntity.builder()
                    .chatId(chatId)
                    .strategyType(type)
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .schemaHash(schemaHash)
                    .modelKey(modelKey)
                    .modelVersion(modelVersion)
                    .metricsJson(metricsJson)
                    .createdAt(createdAt)
                    .build();
            artifactRepo.save(art);
        } catch (Exception e) {
            log.warn("🧠 TRAIN artifact save failed chatId={} type={} err={}", chatId, type, e.toString(), e);
        }
    }

    private void publishSettingsUpdated(Long chatId, StrategyType type, String source) {
        try {
            if (eventPublisher == null || chatId == null || type == null) return;
            String src = normTrim(source);
            if (src == null) src = "ml_train";
            eventPublisher.publishEvent(new StrategySettingsUpdatedEvent(chatId, type, src));
        } catch (Exception e) {
            log.debug("🧠 TRAIN publishSettingsUpdated ignored: {}", e.toString());
        }
    }

    private String cooldownKey(String modelKey) {
        return "train:" + modelKey;
    }

    private List<String> resolveFeatureSchema(List<MlSampleEntity> samples) {
        if (samples == null || samples.isEmpty()) return null;
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        TreeSet<String> extras = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (MlSampleEntity sample : samples) {
            if (sample == null) continue;
            JsonNode meta = sample.getMetaJson();
            appendOrderedKeysFromMetaArray(ordered, meta, "featureSpec");
            appendOrderedKeysFromMetaArray(ordered, meta, "trainFeatureSpec");
            Map<String, Object> merged = resolveTrainingFeatureMap(sample);
            for (String key : merged.keySet()) {
                if (key == null || ordered.contains(key)) continue;
                extras.add(key);
            }
        }
        ordered.addAll(extras);
        return ordered.isEmpty() ? null : new ArrayList<>(ordered);
    }

    private void appendOrderedKeysFromMetaArray(LinkedHashSet<String> ordered, JsonNode meta, String fieldName) {
        if (ordered == null || meta == null || fieldName == null || fieldName.isBlank()) return;
        JsonNode spec = meta.get(fieldName);
        if (spec == null || !spec.isArray() || spec.size() == 0) return;
        for (JsonNode node : spec) {
            if (node == null || node.isNull()) continue;
            String name = normalizeFeatureName(node.asText(null));
            if (name == null) continue;
            ordered.add(name);
        }
    }

    private Map<String, Object> resolveTrainingFeatureMap(MlSampleEntity sample) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (sample == null) return merged;
        @SuppressWarnings("unchecked")
        Map<String, Object> rawFeatures = objectMapper.convertValue(sample.getFeaturesJson(), Map.class);
        merged.putAll(normalizeFeatureMap(rawFeatures));
        JsonNode meta = sample.getMetaJson();
        if (meta != null && meta.isObject()) {
            mergeStrategyParams(merged, meta.get("strategyParams"));
        }
        return merged;
    }

    private void mergeStrategyParams(LinkedHashMap<String, Object> target, JsonNode paramsNode) {
        if (target == null || paramsNode == null || !paramsNode.isObject()) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> rawParams = objectMapper.convertValue(paramsNode, Map.class);
        Map<String, Object> normalized = normalizeFeatureMap(rawParams);
        for (Map.Entry<String, Object> entry : normalized.entrySet()) {
            if (entry.getKey() != null) target.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    private String resolveSampleSchemaHash(MlSampleEntity sample) {
        if (sample == null) return null;
        JsonNode meta = sample.getMetaJson();
        if (meta != null && meta.isObject()) {
            String metaHash = normTrim(textValue(meta.get("schemaHash")));
            if (metaHash != null) return metaHash;
        }
        List<String> schema = resolveFeatureSchema(List.of(sample));
        return computeSchemaHash(schema);
    }

    private static String computeSchemaHash(List<String> keys) {
        if (keys == null || keys.isEmpty()) return null;
        List<String> normalized = new ArrayList<>();
        for (String k : keys) {
            String nk = normKey(k);
            if (nk != null) normalized.add(nk);
        }
        if (normalized.isEmpty()) return null;
        return sha256(String.join("|", normalized));
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static Long sampleTimeMs(MlSampleEntity s) {
        if (s == null) return null;
        if (s.getTs() != null) return s.getTs().toEpochMilli();
        if (s.getCreatedAt() != null) return s.getCreatedAt().toEpochMilli();
        return null;
    }

    private static Integer labelToIntOrNull(String lbl) {
        if (lbl == null) return null;
        String s = lbl.trim();
        if (s.isEmpty()) return null;
        String u = s.toUpperCase(Locale.ROOT);
        if (u.equals("WIN") || u.equals("TP") || u.equals("TAKE_PROFIT") || u.equals("PROFIT") || u.equals("TRUE")) return 1;
        if (u.equals("LOSS") || u.equals("SL") || u.equals("STOP_LOSS") || u.equals("STOP") || u.equals("FALSE")) return 0;
        if (u.equals("YES") || u.equals("Y")) return 1;
        if (u.equals("NO") || u.equals("N")) return 0;
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? 1 : 0;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, Object> normalizeFeatureMap(Map<String, Object> in) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (in == null || in.isEmpty()) return out;
        for (Map.Entry<String, Object> e : in.entrySet()) {
            String key = normalizeFeatureName(e.getKey());
            if (key == null || META_KEYS.contains(key) || LABEL_KEYS.contains(key)) continue;
            out.put(key, normalizeValue(e.getValue()));
        }
        return out;
    }

    private static Object normalizeValue(Object v) {
        if (v == null) return null;
        if (v instanceof Enum<?> en) return en.name();
        if (v instanceof Instant inst) return inst.toEpochMilli();
        if (v instanceof Float f) return Float.isFinite(f) ? (double) f : null;
        if (v instanceof Double d) return Double.isFinite(d) ? d : null;
        return v;
    }

    private static String normalizeFeatureName(String raw) {
        String key = normKey(raw);
        if (key == null) return null;
        return WINDOW_STRATEGY_PARAM_ALIASES.getOrDefault(key, key);
    }

    private static String normKey(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static String normTrim(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static String normUpper(String s) {
        String v = normTrim(s);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String normLower(String s) {
        String v = normTrim(s);
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }

    private static String stringOf(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String textValue(JsonNode n) {
        if (n == null || n.isNull()) return null;
        String s = n.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private record Context(String symbol, String timeframe, String exchange, String network) {}
}




