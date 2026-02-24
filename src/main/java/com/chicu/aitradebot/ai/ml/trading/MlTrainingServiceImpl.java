package com.chicu.aitradebot.ai.ml.trading;

import com.chicu.aitradebot.ai.ml.MlClient;
import com.chicu.aitradebot.ai.ml.MlTrainProperties;
import com.chicu.aitradebot.ai.ml.artifacts.MlModelArtifactEntity;
import com.chicu.aitradebot.ai.ml.artifacts.MlModelArtifactRepository;
import com.chicu.aitradebot.ai.ml.dataset.MlSampleEntity;
import com.chicu.aitradebot.ai.ml.dataset.MlSampleRepository;
import com.chicu.aitradebot.ai.ml.dto.MlTrainRequest;
import com.chicu.aitradebot.ai.ml.dto.MlTrainResponse;
import com.chicu.aitradebot.ai.ml.training.MlTrainingResult;
import com.chicu.aitradebot.ai.ml.training.MlTrainingService;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlTrainingServiceImpl implements MlTrainingService {

    private final MlTrainProperties props;
    private final MlSampleRepository sampleRepo;
    private final MlModelArtifactRepository artifactRepo;
    private final StrategySettingsService strategySettingsService;
    private final MlClient mlClient;

    // простой in-memory cooldown (на прод можно в БД/Redis)
    private final Map<String, Instant> lastTrainAt = new HashMap<>();

    @Override
    public MlTrainingResult trainNow(Long chatId, StrategyType type, String reason) {
        if (!props.isEnabled()) {
            return new MlTrainingResult(false, false, null, null, null, "training_disabled");
        }
        if (chatId == null || type == null) {
            return new MlTrainingResult(false, false, null, null, null, "bad_args");
        }

        String key = chatId + ":" + type.name();
        Instant now = Instant.now();
        Instant last = lastTrainAt.get(key);
        if (last != null && ChronoUnit.MINUTES.between(last, now) < props.getCooldownMinutes()) {
            return new MlTrainingResult(false, false, null, null, null, "cooldown");
        }

        StrategySettings ss = findStrategySettings(chatId, type);
        String symbol = ss.getSymbol();
        String tf = ss.getTimeframe();

        Instant from = now.minus(props.getLookbackDays(), ChronoUnit.DAYS);
        List<MlSampleEntity> samples = sampleRepo.findRecent(chatId, type, from);

        if (samples.size() < props.getMinSamples()) {
            return new MlTrainingResult(false, false, null, null, null,
                    "not_enough_samples=" + samples.size());
        }

        // schemaHash должен соответствовать фичам (пока берём из StrategySettings, позже — из FeatureBuilder)
        String schemaHash = ss.getMlSchemaHash();
        if (schemaHash == null || schemaHash.isBlank()) {
            // на проде лучше считать хэш схемы фич (имена+типы).
            // но даже это — лучше чем “угадывать”.
            schemaHash = "features_v1";
        }

        MlTrainRequest req = new MlTrainRequest();
        req.setChatId(chatId);
        req.setStrategyType(type.name());
        req.setSymbol(symbol);
        req.setTimeframe(tf);
        req.setSchemaHash(schemaHash);
        req.setRows(toRows(samples));
        req.setParams(Map.of("reason", reason == null ? "manual" : reason));

        MlTrainResponse resp;
        try {
            resp = mlClient.train(req);
        } catch (Exception e) {
            log.warn("🧠 TRAIN failed: chatId={} type={} err={}", chatId, type, e.toString());
            return new MlTrainingResult(false, false, null, null, null, "train_exception");
        }

        if (resp == null || !resp.isOk()) {
            return new MlTrainingResult(false, false, null, null, null,
                    resp != null ? resp.getError() : "train_null");
        }

        // 1) сохраняем артефакт
        MlModelArtifactEntity art = MlModelArtifactEntity.builder()
                .chatId(chatId)
                .strategyType(type)
                .symbol(symbol)
                .timeframe(tf)

                .modelKey(resp.getModelKey())
                .modelVersion(resp.getModelVersion())

                .createdAt(now)
                .build();
        artifactRepo.save(art);

        boolean applied = false;

        // 2) обновляем StrategySettings (modelKey/modelVersion/schemaHash) и включаем gate при autoApply
        if (props.isAutoApply()) {
            ss.setMlModelKey(resp.getModelKey());
            ss.setMlModelVersion(resp.getModelVersion());

            ss.setMlGateEnabled(true);
            strategySettingsService.save(ss);
            applied = true;
        }

        lastTrainAt.put(key, now);

        log.info("🧠 TRAIN OK chatId={} type={} applied={} modelKey={} ver={}",
                chatId, type, applied, resp.getModelKey(), resp.getModelVersion());

        return new MlTrainingResult(true, applied, resp.getModelKey(), resp.getModelVersion(), resp.getSchemaHash(), null);
    }

    private StrategySettings findStrategySettings(Long chatId, StrategyType type) {
        return strategySettingsService.findAllByChatId(chatId)
                .stream()
                .filter(s -> s.getType() == type)
                .max(Comparator.comparing(StrategySettings::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElseThrow(() -> new IllegalStateException("StrategySettings not found chatId=" + chatId + " type=" + type));
    }

    private List<Map<String, Object>> toRows(List<MlSampleEntity> samples) {
        // featuresJson — JSON строка. На проде лучше хранить JSONB и читать мапой, но сейчас:
        // - быстро
        // - предсказуемо
        // - не ломает схему
        List<Map<String, Object>> rows = new ArrayList<>(samples.size());
        for (MlSampleEntity s : samples) {
            Map<String, Object> row = new HashMap<>();
            row.put("featuresJson", s.getFeaturesJson());
            row.put("label", s.getLabel());
            rows.add(row);
        }
        return rows;
    }
}
