package com.chicu.aitradebot.ai.ml.training;

import com.chicu.aitradebot.ai.ml.MlClient;
import com.chicu.aitradebot.ai.ml.MlTrainProperties;
import com.chicu.aitradebot.ai.ml.artifacts.MlModelArtifactEntity;
import com.chicu.aitradebot.ai.ml.artifacts.MlModelArtifactRepository;
import com.chicu.aitradebot.ai.ml.dataset.MlSampleEntity;
import com.chicu.aitradebot.ai.ml.dataset.MlSampleRepository;
import com.chicu.aitradebot.ai.ml.dto.MlTrainRequest;
import com.chicu.aitradebot.ai.ml.dto.MlTrainResponse;
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

    private final MlTrainProperties props;
    private final MlSampleRepository sampleRepo;
    private final MlModelArtifactRepository artifactRepo;
    private final StrategySettingsService strategySettingsService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<MlClient> mlClientProvider;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * cooldown по конкретному контексту
     */
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
            log.warn("🧠 TRAIN SKIP: strategy settings null chatId={} type={}", chatId, type);
            return new MlTrainingResult(false, false, null, null, null, "strategy_settings_missing");
        }

        String reasonNorm = normTrim(reason);
        if (reasonNorm == null) {
            reasonNorm = "auto";
        }

        Instant now = Instant.now();
        Instant from = now.minus(Math.max(1, props.getLookbackDays()), ChronoUnit.DAYS);

        String symbol = normUpper(ss.getSymbol());
        String timeframe = normLower(ss.getTimeframe());

        // если в настройках пусто — пробуем взять из samples
        if (symbol == null || timeframe == null) {
            Pair inferred = inferSymbolTfFromSamples(chatId, type, from);
            if (symbol == null) {
                symbol = inferred.symbol();
            }
            if (timeframe == null) {
                timeframe = inferred.timeframe();
            }
        }

        if (symbol == null) {
            log.warn("🧠 TRAIN SKIP: symbol missing chatId={} type={}", chatId, type);
            return new MlTrainingResult(false, false, null, null, null, "symbol_missing");
        }

        if (timeframe == null) {
            log.warn("🧠 TRAIN SKIP: timeframe missing chatId={} type={} symbol={}", chatId, type, symbol);
            return new MlTrainingResult(false, false, null, null, null, "timeframe_missing");
        }

        String cooldownKey = cooldownKey(chatId, type, symbol, timeframe);
        Instant last = lastTrainAt.get(cooldownKey);
        long cooldownMinutes = Math.max(0, props.getCooldownMinutes());

        if (last != null && cooldownMinutes > 0) {
            long passed = ChronoUnit.MINUTES.between(last, now);
            if (passed < cooldownMinutes) {
                log.info("🧠 TRAIN SKIP: cooldown chatId={} type={} sym={} tf={} passed={}m need={}m",
                        chatId, type, symbol, timeframe, passed, cooldownMinutes);
                return new MlTrainingResult(false, false, null, null, null, "cooldown");
            }
        }

        List<MlSampleEntity> recent = safeFindRecent(chatId, type, from);
        if (recent.isEmpty()) {
            log.warn("🧠 TRAIN SKIP: no recent samples chatId={} type={} from={}", chatId, type, from);
            return new MlTrainingResult(false, false, null, null, null, "no_samples");
        }

        List<MlSampleEntity> filtered = new ArrayList<>();
        for (MlSampleEntity s : recent) {
            if (s == null) continue;
            if (s.getFeaturesJson() == null || !s.getFeaturesJson().isObject()) continue;

            Integer label = labelToIntOrNull(s.getLabel());
            if (label == null) continue;

            String sSym = normUpper(s.getSymbol());
            String sTf = normLower(s.getTimeframe());

            if (sSym != null && !symbol.equals(sSym)) continue;
            if (sTf != null && !timeframe.equals(sTf)) continue;

            filtered.add(s);
        }

        if (filtered.size() < props.getMinSamples()) {
            log.warn("🧠 TRAIN SKIP: not enough samples chatId={} type={} sym={} tf={} samples={} minSamples={}",
                    chatId, type, symbol, timeframe, filtered.size(), props.getMinSamples());
            return new MlTrainingResult(
                    false,
                    false,
                    null,
                    null,
                    null,
                    "not_enough_samples=" + filtered.size()
            );
        }

        int rowsLimit = Math.max(100, props.getRowsLimit());
        filtered.sort(Comparator.comparing(MlTrainingServiceImpl::sampleTimeMs, Comparator.nullsLast(Comparator.reverseOrder())));
        if (filtered.size() > rowsLimit) {
            filtered = new ArrayList<>(filtered.subList(0, rowsLimit));
        }
        filtered.sort(Comparator.comparing(MlTrainingServiceImpl::sampleTimeMs, Comparator.nullsLast(Comparator.naturalOrder())));

        List<String> featureSchema = resolveFeatureSchema(filtered);
        if (featureSchema == null || featureSchema.isEmpty()) {
            log.warn("🧠 TRAIN SKIP: feature schema missing chatId={} type={} sym={} tf={}",
                    chatId, type, symbol, timeframe);
            return new MlTrainingResult(false, false, null, null, null, "feature_schema_missing");
        }

        String computedSchemaHash = computeSchemaHash(featureSchema);

        String settingsSchemaHash = normTrim(ss.getMlSchemaHash());
        String finalRequestSchemaHash = computedSchemaHash != null ? computedSchemaHash : settingsSchemaHash;
        if (finalRequestSchemaHash == null) {
            finalRequestSchemaHash = "schema_v1";
        }

        String modelKey = normTrim(ss.getMlModelKey());
        if (modelKey == null) {
            modelKey = buildModelKey(type, symbol, timeframe);
        }

        boolean settingsChangedBeforeTrain = false;

        if (!Objects.equals(normTrim(ss.getMlModelKey()), modelKey)) {
            ss.setMlModelKey(modelKey);
            settingsChangedBeforeTrain = true;
        }

        if (!Objects.equals(normTrim(ss.getMlSchemaHash()), finalRequestSchemaHash)) {
            ss.setMlSchemaHash(finalRequestSchemaHash);
            settingsChangedBeforeTrain = true;
        }

        if (settingsChangedBeforeTrain) {
            try {
                ss = strategySettingsService.save(ss);
            } catch (Exception e) {
                log.warn("🧠 TRAIN pre-save settings failed chatId={} type={} err={}", chatId, type, e.toString());
            }
        }

        List<Map<String, Object>> rows = toRows(filtered, featureSchema);
        if (rows.isEmpty()) {
            log.warn("🧠 TRAIN SKIP: rows empty after normalization chatId={} type={} sym={} tf={}",
                    chatId, type, symbol, timeframe);
            return new MlTrainingResult(false, false, modelKey, null, finalRequestSchemaHash, "rows_empty");
        }

        MlTrainRequest req = new MlTrainRequest();
        req.setChatId(chatId);
        req.setStrategyType(type.name());
        req.setSymbol(symbol);
        req.setTimeframe(timeframe);
        req.setModelKey(modelKey);
        req.setSchemaHash(finalRequestSchemaHash);
        req.setFeatureSchema(featureSchema);
        req.setRows(rows);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reason", reasonNorm);
        params.put("rows", rows.size());
        params.put("from", from.toEpochMilli());
        params.put("to", now.toEpochMilli());
        params.put("modelKey", modelKey);
        params.put("schemaHash", finalRequestSchemaHash);
        req.setParams(params);

        log.info("🧠 TRAIN START chatId={} type={} sym={} tf={} rows={} schemaSize={} schemaHash={} modelKey={} reason={}",
                chatId, type, symbol, timeframe, rows.size(), featureSchema.size(), finalRequestSchemaHash, modelKey, reasonNorm);

        MlTrainResponse resp;
        try {
            resp = mlClient.train(req);
        } catch (Exception e) {
            log.warn("🧠 TRAIN exception chatId={} type={} sym={} tf={} err={}",
                    chatId, type, symbol, timeframe, e.toString(), e);
            return new MlTrainingResult(false, false, modelKey, null, finalRequestSchemaHash, "train_exception");
        }

        if (resp == null) {
            log.warn("🧠 TRAIN FAIL: response is null chatId={} type={} sym={} tf={}",
                    chatId, type, symbol, timeframe);
            return new MlTrainingResult(false, false, modelKey, null, finalRequestSchemaHash, "train_null");
        }

        if (!resp.isOk()) {
            String error = normTrim(resp.getError()) != null ? resp.getError() : "train_not_ok";
            log.warn("🧠 TRAIN FAIL: not_ok chatId={} type={} sym={} tf={} err={} modelKey={} schemaHash={}",
                    chatId, type, symbol, timeframe, error, modelKey, finalRequestSchemaHash);
            return new MlTrainingResult(
                    false,
                    false,
                    modelKey,
                    null,
                    finalRequestSchemaHash,
                    error
            );
        }

        String responseModelKey = normTrim(resp.getModelKey());
        if (responseModelKey == null) {
            responseModelKey = modelKey;
        }

        String responseModelVersion = normTrim(resp.getModelVersion());

        String responseSchemaHash = normTrim(resp.getSchemaHash());
        String finalSchemaHash = responseSchemaHash != null ? responseSchemaHash : finalRequestSchemaHash;

        log.info("🧠 TRAIN RESPONSE ok=true chatId={} type={} sym={} tf={} modelKey={} modelVersion={} schemaHash={}",
                chatId, type, symbol, timeframe, responseModelKey, responseModelVersion, finalSchemaHash);

        saveArtifactSafe(
                chatId,
                type,
                symbol,
                timeframe,
                responseModelKey,
                responseModelVersion,
                finalSchemaHash,
                resp.getMetricsJson(),
                now
        );

        boolean applied = false;
        try {
            AdvancedControlMode mode = ss.getAdvancedControlMode() != null
                    ? ss.getAdvancedControlMode()
                    : AdvancedControlMode.MANUAL;

            ss.setMlModelKey(responseModelKey);
            ss.setMlModelVersion(responseModelVersion);
            ss.setMlSchemaHash(finalSchemaHash);

            BigDecimal minProb = ss.getGateMinProb();
            if ((minProb == null || minProb.signum() <= 0) && props.getThresholdAutoEnable() > 0) {
                ss.setGateMinProb(
                        BigDecimal.valueOf(props.getThresholdAutoEnable())
                                .setScale(6, RoundingMode.HALF_UP)
                );
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

        // cooldown ставим только после успешного train
        lastTrainAt.put(cooldownKey, now);

        log.info("🧠 TRAIN DONE chatId={} type={} sym={} tf={} applied={} modelKey={} ver={} schemaHash={}",
                chatId, type, symbol, timeframe, applied, responseModelKey, responseModelVersion, finalSchemaHash);

        return new MlTrainingResult(
                true,
                applied,
                responseModelKey,
                responseModelVersion,
                finalSchemaHash,
                null
        );
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

    private List<Map<String, Object>> toRows(List<MlSampleEntity> samples, List<String> featureSchema) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (samples == null || samples.isEmpty() || featureSchema == null || featureSchema.isEmpty()) {
            return rows;
        }

        for (MlSampleEntity s : samples) {
            if (s == null || s.getFeaturesJson() == null || !s.getFeaturesJson().isObject()) {
                continue;
            }

            Integer y = labelToIntOrNull(s.getLabel());
            if (y == null) {
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.convertValue(s.getFeaturesJson(), Map.class);

            Map<String, Object> features = normalizeFeatureMap(raw);
            if (features.isEmpty()) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            boolean hasAll = true;

            for (String key : featureSchema) {
                if (!features.containsKey(key)) {
                    hasAll = false;
                    break;
                }
                row.put(key, features.get(key));
            }

            if (!hasAll) {
                continue;
            }

            row.put("y", y);

            if (s.getTs() != null) {
                row.put("tsMs", s.getTs().toEpochMilli());
            } else if (s.getCreatedAt() != null) {
                row.put("tsMs", s.getCreatedAt().toEpochMilli());
            }

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

            log.info("🧠 TRAIN artifact saved chatId={} type={} sym={} tf={} modelKey={} modelVersion={}",
                    chatId, type, symbol, timeframe, modelKey, modelVersion);

        } catch (Exception e) {
            log.warn("🧠 TRAIN artifact save failed chatId={} type={} err={}", chatId, type, e.toString(), e);
        }
    }

    private void publishSettingsUpdated(Long chatId, StrategyType type, String source) {
        try {
            if (eventPublisher == null || chatId == null || type == null) {
                return;
            }

            String src = normTrim(source);
            if (src == null) {
                src = "ml_train";
            }

            eventPublisher.publishEvent(new StrategySettingsUpdatedEvent(chatId, type, src));
        } catch (Exception e) {
            log.debug("🧠 TRAIN publishSettingsUpdated ignored: {}", e.toString());
        }
    }

    private static String cooldownKey(Long chatId, StrategyType type, String symbol, String tf) {
        return chatId + ":" + type.name() + ":" + symbol + ":" + tf;
    }

    private static String buildModelKey(StrategyType type, String symbol, String tf) {
        return type.name() + ":" + symbol + ":" + tf;
    }

    private static List<String> resolveFeatureSchema(List<MlSampleEntity> samples) {
        if (samples == null || samples.isEmpty()) {
            return null;
        }

        for (MlSampleEntity s : samples) {
            if (s == null || s.getMetaJson() == null) continue;

            JsonNode spec = s.getMetaJson().get("featureSpec");
            if (spec != null && spec.isArray() && spec.size() > 0) {
                LinkedHashSet<String> ordered = new LinkedHashSet<>();

                for (JsonNode n : spec) {
                    if (n == null || n.isNull()) continue;
                    String name = normKey(n.asText(null));
                    if (name == null) continue;
                    if (META_KEYS.contains(name)) continue;
                    if (LABEL_KEYS.contains(name)) continue;
                    ordered.add(name);
                }

                if (!ordered.isEmpty()) {
                    return new ArrayList<>(ordered);
                }
            }
        }

        TreeSet<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        for (MlSampleEntity s : samples) {
            JsonNode fj = s != null ? s.getFeaturesJson() : null;
            if (fj == null || !fj.isObject()) continue;

            Iterator<String> it = fj.fieldNames();
            while (it.hasNext()) {
                String raw = it.next();
                String key = normKey(raw);
                if (key == null) continue;
                if (META_KEYS.contains(key)) continue;
                if (LABEL_KEYS.contains(key)) continue;
                all.add(key);
            }
        }

        if (all.isEmpty()) {
            return null;
        }

        return new ArrayList<>(all);
    }

    private static String computeSchemaHash(List<String> keys) {
        if (keys == null || keys.isEmpty()) return null;

        List<String> normalized = new ArrayList<>();
        for (String k : keys) {
            String nk = normKey(k);
            if (nk != null) {
                normalized.add(nk);
            }
        }

        if (normalized.isEmpty()) return null;

        return sha256(String.join("|", normalized));
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(String.format("%02x", b));
            }
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

    private record Pair(String symbol, String timeframe) {}

    private Pair inferSymbolTfFromSamples(Long chatId, StrategyType type, Instant from) {
        try {
            List<MlSampleEntity> recent = safeFindRecent(chatId, type, from);
            if (recent.isEmpty()) {
                return new Pair(null, null);
            }

            recent.sort(Comparator.comparing(MlTrainingServiceImpl::sampleTimeMs, Comparator.nullsLast(Comparator.reverseOrder())));

            for (MlSampleEntity s : recent) {
                if (s == null) continue;
                if (s.getFeaturesJson() == null || !s.getFeaturesJson().isObject()) continue;
                if (labelToIntOrNull(s.getLabel()) == null) continue;

                String sym = normUpper(s.getSymbol());
                String tf = normLower(s.getTimeframe());

                if (sym != null && tf != null) {
                    return new Pair(sym, tf);
                }
            }
        } catch (Exception ignored) {
        }

        return new Pair(null, null);
    }

    private static Integer labelToIntOrNull(String lbl) {
        if (lbl == null) return null;

        String s = lbl.trim();
        if (s.isEmpty()) return null;

        String u = s.toUpperCase(Locale.ROOT);

        if (u.equals("WIN") || u.equals("TP") || u.equals("TAKE_PROFIT") || u.equals("PROFIT") || u.equals("TRUE")) {
            return 1;
        }

        if (u.equals("LOSS") || u.equals("SL") || u.equals("STOP_LOSS") || u.equals("STOP") || u.equals("FALSE")) {
            return 0;
        }

        if (u.equals("YES") || u.equals("Y")) {
            return 1;
        }

        if (u.equals("NO") || u.equals("N")) {
            return 0;
        }

        try {
            int v = Integer.parseInt(s);
            return v > 0 ? 1 : 0;
        } catch (Exception ignored) {
        }

        return null;
    }

    private static Map<String, Object> normalizeFeatureMap(Map<String, Object> in) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (in == null || in.isEmpty()) {
            return out;
        }

        for (Map.Entry<String, Object> e : in.entrySet()) {
            String key = normKey(e.getKey());
            if (key == null) continue;
            if (META_KEYS.contains(key)) continue;
            if (LABEL_KEYS.contains(key)) continue;

            Object value = normalizeValue(e.getValue());
            out.put(key, value);
        }

        return out;
    }

    private static Object normalizeValue(Object v) {
        if (v == null) return null;

        if (v instanceof Enum<?> en) {
            return en.name();
        }

        if (v instanceof Instant inst) {
            return inst.toEpochMilli();
        }

        if (v instanceof Float f) {
            if (!Float.isFinite(f)) return null;
            return (double) f;
        }

        if (v instanceof Double d) {
            if (!Double.isFinite(d)) return null;
            return d;
        }

        return v;
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
}