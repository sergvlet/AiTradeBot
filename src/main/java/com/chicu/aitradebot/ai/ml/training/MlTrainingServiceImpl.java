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
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.events.StrategySettingsUpdatedEvent;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
            ss = strategySettingsService.getOrCreate(chatId, type);
        } catch (Exception e) {
            log.warn("🧠 TRAIN getOrCreate settings failed chatId={} type={} err={}", chatId, type, e.toString());
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

        String modelKey = normTrim(ss.getMlModelKey());
        if (modelKey == null) {
            modelKey = MlGateway.buildContextModelKey(type, exchange, network, symbol, timeframe);
        }

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

        String responseModelKey = normTrim(resp.getModelKey());
        if (responseModelKey == null) responseModelKey = modelKey;
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
