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
            return new MlTrainingResult(false, false, null, null, null, "training_disabled");
        }
        if (chatId == null || chatId <= 0 || type == null) {
            return new MlTrainingResult(false, false, null, null, null, "bad_args");
        }

        MlClient mlClient = mlClientProvider != null ? mlClientProvider.getIfAvailable() : null;
        if (mlClient == null) {
            return new MlTrainingResult(false, false, null, null, null, "ml_client_missing");
        }

        StrategySettings ss = strategySettingsService.getOrCreate(chatId, type);
        if (ss == null) {
            return new MlTrainingResult(false, false, null, null, null, "strategy_settings_missing");
        }

        String reasonNorm = (reason == null || reason.isBlank()) ? "manual" : reason.trim();

        Instant now = Instant.now();
        Instant from = now.minus(Math.max(1, props.getLookbackDays()), ChronoUnit.DAYS);

        String symbol = normUpper(ss.getSymbol());
        String tf = normLower(ss.getTimeframe());

        if (symbol == null || tf == null) {
            Pair symTf = inferSymbolTfFromSamples(chatId, type, from);
            if (symbol == null) symbol = symTf.symbol;
            if (tf == null) tf = symTf.timeframe;
        }

        if (symbol == null) return new MlTrainingResult(false, false, null, null, null, "symbol_missing");
        if (tf == null) return new MlTrainingResult(false, false, null, null, null, "timeframe_missing");

        String cdKey = cooldownKey(chatId, type, symbol, tf);
        Instant last = lastTrainAt.get(cdKey);
        long cooldownMin = Math.max(0, props.getCooldownMinutes());
        if (last != null && ChronoUnit.MINUTES.between(last, now) < cooldownMin) {
            return new MlTrainingResult(false, false, null, null, null, "cooldown");
        }

        List<MlSampleEntity> recent = safeFindRecent(chatId, type, from);

        List<MlSampleEntity> samples = new ArrayList<>();
        for (MlSampleEntity s : recent) {
            if (s == null) continue;
            if (s.getFeaturesJson() == null) continue;

            Integer y = labelToIntOrNull(s.getLabel());
            if (y == null) continue;

            String sSym = normUpper(s.getSymbol());
            String sTf = normLower(s.getTimeframe());

            if (sSym != null && !symbol.equals(sSym)) continue;
            if (sTf != null && !tf.equals(sTf)) continue;

            samples.add(s);
        }

        if (samples.size() < props.getMinSamples()) {
            return new MlTrainingResult(false, false, null, null, null, "not_enough_samples=" + samples.size());
        }

        int rowsLimit = Math.max(100, props.getRowsLimit());
        samples.sort(Comparator.comparing(MlTrainingServiceImpl::sampleTimeMs, Comparator.nullsLast(Comparator.reverseOrder())));
        if (samples.size() > rowsLimit) {
            samples = new ArrayList<>(samples.subList(0, rowsLimit));
        }
        samples.sort(Comparator.comparing(MlTrainingServiceImpl::sampleTimeMs, Comparator.nullsLast(Comparator.naturalOrder())));

        List<String> featureSchema = resolveFeatureSchema(samples);
        if (featureSchema == null || featureSchema.isEmpty()) {
            return new MlTrainingResult(false, false, null, null, null, "feature_schema_missing");
        }

        String schemaHash = computeSchemaHash(featureSchema);
        if (schemaHash == null) schemaHash = normTrim(ss.getMlSchemaHash());
        if (schemaHash == null) schemaHash = "schema_v1";

        if (!Objects.equals(normTrim(ss.getMlSchemaHash()), schemaHash)) {
            ss.setMlSchemaHash(schemaHash);
            try {
                ss = strategySettingsService.save(ss);
            } catch (Exception ignored) {
            }
        }

        String modelKey = normTrim(ss.getMlModelKey());
        if (modelKey == null) {
            modelKey = type.name() + ":" + symbol + ":" + tf;
        }

        MlTrainRequest req = new MlTrainRequest();
        req.setChatId(chatId);
        req.setStrategyType(type.name());
        req.setSymbol(symbol);
        req.setTimeframe(tf);
        req.setModelKey(modelKey);
        req.setSchemaHash(schemaHash);
        req.setFeatureSchema(featureSchema);
        req.setRows(toRows(samples, featureSchema));

        Map<String, Object> params = new HashMap<>();
        params.put("reason", reasonNorm);
        params.put("rows", req.getRows() != null ? req.getRows().size() : 0);
        params.put("from", from.toEpochMilli());
        params.put("to", now.toEpochMilli());
        params.put("modelKey", modelKey);
        req.setParams(params);

        log.info("🧠 TRAIN START chatId={} type={} sym={} tf={} rows={} schemaHash={} modelKey={} reason={}",
                chatId, type, symbol, tf, params.get("rows"), schemaHash, modelKey, reasonNorm);

        MlTrainResponse resp;
        try {
            resp = mlClient.train(req);
        } catch (Exception e) {
            log.warn("🧠 TRAIN exception chatId={} type={} err={}", chatId, type, e.toString());
            return new MlTrainingResult(false, false, modelKey, null, schemaHash, "train_exception");
        }

        if (resp == null || !resp.isOk()) {
            return new MlTrainingResult(false, false, modelKey, null, schemaHash,
                    resp != null ? resp.getError() : "train_null");
        }

        String respSchema = normTrim(resp.getSchemaHash());
        String finalSchemaHash = (respSchema != null ? respSchema : schemaHash);

        try {
            MlModelArtifactEntity art = MlModelArtifactEntity.builder()
                    .chatId(chatId)
                    .strategyType(type)
                    .symbol(symbol)
                    .timeframe(tf)
                    .schemaHash(finalSchemaHash)
                    .modelKey(resp.getModelKey() != null ? resp.getModelKey() : modelKey)
                    .modelVersion(resp.getModelVersion())
                    .metricsJson(resp.getMetricsJson())
                    .createdAt(now)
                    .build();
            artifactRepo.save(art);
        } catch (Exception e) {
            log.warn("🧠 TRAIN artifact save failed chatId={} type={} err={}", chatId, type, e.toString());
        }

        boolean applied = false;

        if (props.isAutoApply()) {
            AdvancedControlMode mode = ss.getAdvancedControlMode() != null
                    ? ss.getAdvancedControlMode()
                    : AdvancedControlMode.MANUAL;

            ss.setMlModelKey(resp.getModelKey() != null ? resp.getModelKey() : modelKey);
            ss.setMlModelVersion(resp.getModelVersion());
            ss.setMlSchemaHash(finalSchemaHash);

            try {
                BigDecimal minProb = ss.getGateMinProb();
                if ((minProb == null || minProb.signum() <= 0) && props.getThresholdAutoEnable() > 0) {
                    ss.setGateMinProb(BigDecimal.valueOf(props.getThresholdAutoEnable())
                            .setScale(6, RoundingMode.HALF_UP));
                }
            } catch (Exception ignored) {
            }

            if (mode != AdvancedControlMode.MANUAL) {
                ss.setMlGateEnabled(true);
                try {
                    ss = strategySettingsService.save(ss);
                    applied = true;
                } catch (Exception e) {
                    log.warn("🧠 TRAIN apply settings failed chatId={} type={} err={}", chatId, type, e.toString());
                }
            } else {
                try {
                    strategySettingsService.save(ss);
                } catch (Exception ignored) {
                }
            }

            publishSettingsUpdated(chatId, type, "ml_train:" + reasonNorm);
        }

        lastTrainAt.put(cdKey, now);

        log.info("🧠 TRAIN DONE chatId={} type={} applied={} modelKey={} ver={} schemaHash={}",
                chatId, type, applied, (resp.getModelKey() != null ? resp.getModelKey() : modelKey), resp.getModelVersion(), finalSchemaHash);

        return new MlTrainingResult(true, applied,
                (resp.getModelKey() != null ? resp.getModelKey() : modelKey),
                resp.getModelVersion(),
                finalSchemaHash,
                null);
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
        List<Map<String, Object>> rows = new ArrayList<>(samples.size());

        for (MlSampleEntity s : samples) {
            JsonNode fj = s.getFeaturesJson();
            if (fj == null) continue;

            Integer y = labelToIntOrNull(s.getLabel());
            if (y == null) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> features = objectMapper.convertValue(fj, Map.class);

            Map<String, Object> row = new LinkedHashMap<>();

            if (featureSchema != null && !featureSchema.isEmpty()) {
                for (String k : featureSchema) {
                    row.put(k, features.get(k));
                }
            } else {
                TreeMap<String, Object> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                sorted.putAll(features);
                row.putAll(sorted);
            }

            // ✅ Только один label-ключ.
            row.put("y", y);

            if (s.getTs() != null) {
                row.put("tsMs", s.getTs().toEpochMilli());
            }

            rows.add(row);
        }

        return rows;
    }

    private void publishSettingsUpdated(Long chatId, StrategyType type, String source) {
        try {
            if (eventPublisher == null) return;
            long cid = chatId != null ? chatId : 0L;
            String src = (source == null || source.isBlank()) ? "ml_train" : source.trim();
            eventPublisher.publishEvent(new StrategySettingsUpdatedEvent(cid, type, src));
        } catch (Exception ignored) {
        }
    }

    private static String cooldownKey(Long chatId, StrategyType type, String symbol, String tf) {
        return chatId + ":" + type.name() + ":" + symbol + ":" + tf;
    }

    private static List<String> resolveFeatureSchema(List<MlSampleEntity> samples) {
        if (samples == null || samples.isEmpty()) return null;

        JsonNode meta = samples.get(0).getMetaJson();
        if (meta != null) {
            JsonNode spec = meta.get("featureSpec");
            if (spec != null && spec.isArray() && spec.size() > 0) {
                List<String> out = new ArrayList<>();
                for (JsonNode n : spec) {
                    if (n == null || n.isNull()) continue;
                    String s = n.asText(null);
                    if (s != null && !s.isBlank()) out.add(s.trim());
                }
                if (!out.isEmpty()) return out;
            }
        }

        JsonNode fj = samples.get(0).getFeaturesJson();
        if (fj != null && fj.isObject()) {
            Iterator<String> it = fj.fieldNames();
            List<String> keys = new ArrayList<>();
            while (it.hasNext()) keys.add(it.next());
            keys.sort(String.CASE_INSENSITIVE_ORDER);
            return keys.isEmpty() ? null : keys;
        }

        try {
            Set<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (MlSampleEntity s : samples) {
                JsonNode x = s != null ? s.getFeaturesJson() : null;
                if (x == null || !x.isObject()) continue;
                Iterator<String> it = x.fieldNames();
                while (it.hasNext()) all.add(it.next());
            }
            if (!all.isEmpty()) return new ArrayList<>(all);
        } catch (Exception ignored) {
        }

        return null;
    }

    private static String computeSchemaHash(List<String> keys) {
        if (keys == null || keys.isEmpty()) return null;
        return sha256(String.join(",", keys));
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "sha256_error";
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
            List<MlSampleEntity> r = safeFindRecent(chatId, type, from);
            if (r == null || r.isEmpty()) return new Pair(null, null);

            r.sort(Comparator.comparing(MlTrainingServiceImpl::sampleTimeMs, Comparator.nullsLast(Comparator.reverseOrder())));
            for (MlSampleEntity s : r) {
                String sym = normUpper(s.getSymbol());
                String tf = normLower(s.getTimeframe());
                if (sym != null && tf != null) return new Pair(sym, tf);
            }
            return new Pair(null, null);
        } catch (Exception ignored) {
            return new Pair(null, null);
        }
    }

    private static Integer labelToIntOrNull(String lbl) {
        if (lbl == null) return null;
        String s = lbl.trim();
        if (s.isEmpty()) return null;

        String u = s.toUpperCase(Locale.ROOT);

        if (u.equals("WIN") || u.equals("TP") || u.equals("TAKE_PROFIT") || u.equals("PROFIT") || u.equals("TRUE")) return 1;
        if (u.equals("LOSS") || u.equals("SL") || u.equals("STOP_LOSS") || u.equals("STOP") || u.equals("FALSE")) return 0;

        try {
            int v = Integer.parseInt(s);
            return v > 0 ? 1 : 0;
        } catch (Exception ignored) {
        }

        if (u.equals("YES") || u.equals("Y")) return 1;
        if (u.equals("NO") || u.equals("N")) return 0;

        return null;
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