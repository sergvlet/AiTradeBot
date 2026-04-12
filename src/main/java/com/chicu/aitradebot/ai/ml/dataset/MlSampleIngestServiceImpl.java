package com.chicu.aitradebot.ai.ml.dataset;

import com.chicu.aitradebot.ai.ml.MlTrainProperties;
import com.chicu.aitradebot.ai.ml.training.MlTrainingResult;
import com.chicu.aitradebot.ai.ml.training.MlTrainingService;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlSampleIngestServiceImpl implements MlSampleIngestService {

    private final MlSampleRepository sampleRepository;
    private final MlTrainingService mlTrainingService;
    private final MlTrainProperties trainProperties;

    /**
     * Защита от параллельного train по одному и тому же КОНТЕКСТУ модели.
     * Важно: ключ без chatId, потому что модель cohort-уровня и должна быть общей
     * для одного контекста (strategy + exchange + network + symbol + timeframe).
     */
    private final Map<String, AtomicBoolean> trainingNow = new ConcurrentHashMap<>();

    /**
     * Локальный soft-cooldown по контексту модели.
     */
    private final Map<String, Instant> lastAttemptAt = new ConcurrentHashMap<>();

    /**
     * Сколько trainable samples было при последней осмысленной попытке retrain
     * по конкретному контексту модели.
     */
    private final Map<String, Long> lastTriggeredTrainableCount = new ConcurrentHashMap<>();

    @Override
    public MlSampleEntity save(MlSampleEntity sample) {
        if (sample == null) {
            throw new IllegalArgumentException("sample is null");
        }

        normalizeSample(sample);
        return sampleRepository.save(sample);
    }

    @Override
    public MlSampleEntity saveAndMaybeTrain(MlSampleEntity sample) {
        if (sample == null) {
            throw new IllegalArgumentException("sample is null");
        }

        normalizeSample(sample);

        MlSampleEntity saved = sampleRepository.save(sample);

        try {
            maybeTriggerTraining(saved);
        } catch (Exception e) {
            log.warn("🧠 SAMPLE save ok, but maybeTrain failed: chatId={} type={} ex={} net={} symbol={} tf={} err={}",
                    saved.getChatId(),
                    saved.getStrategyType(),
                    saved.getExchange(),
                    saved.getNetwork(),
                    saved.getSymbol(),
                    saved.getTimeframe(),
                    e.toString(),
                    e);
        }

        return saved;
    }

    private void maybeTriggerTraining(MlSampleEntity sample) {
        if (sample == null) return;
        if (trainProperties == null || !trainProperties.isEnabled()) return;

        Long chatId = sample.getChatId();
        StrategyType type = sample.getStrategyType();
        String exchange = normUpper(sample.getExchange());
        String network = normUpper(sample.getNetwork());
        String symbol = normUpper(sample.getSymbol());
        String timeframe = normLower(sample.getTimeframe());
        String label = normTrim(sample.getLabel());

        if (chatId == null || chatId <= 0 || type == null) return;
        if (symbol == null || timeframe == null) return;

        // Нет label -> нет trainable sample
        if (label == null) {
            return;
        }

        String contextKey = buildContextKey(type, exchange, network, symbol, timeframe);

        AtomicBoolean lock = trainingNow.computeIfAbsent(contextKey, k -> new AtomicBoolean(false));
        if (lock.get()) {
            return;
        }

        Instant now = Instant.now();

        long cooldownMinutes = Math.max(1, trainProperties.getCooldownMinutes());
        Instant lastAttempt = lastAttemptAt.get(contextKey);
        if (lastAttempt != null && lastAttempt.plus(cooldownMinutes, ChronoUnit.MINUTES).isAfter(now)) {
            return;
        }

        Instant from = now.minus(Math.max(1, trainProperties.getLookbackDays()), ChronoUnit.DAYS);

        List<MlSampleEntity> trainable;
        try {
            trainable = sampleRepository.findForTrainingByContext(
                    type,
                    symbol,
                    timeframe,
                    exchange,
                    network,
                    from
            );
        } catch (Exception e) {
            log.warn("🧠 SAMPLE findForTrainingByContext failed chatId={} type={} ex={} net={} symbol={} tf={} from={} err={}",
                    chatId, type, exchange, network, symbol, timeframe, from, e.toString(), e);
            return;
        }

        long trainableCount = trainable != null ? trainable.size() : 0L;
        int minSamples = Math.max(10, trainProperties.getMinSamples());
        long prevTriggeredCount = lastTriggeredTrainableCount.getOrDefault(contextKey, 0L);
        long retrainStep = resolveRetrainStep(minSamples);

        if (trainableCount < minSamples) {
            if (trainableCount == 1 || trainableCount % 5 == 0) {
                log.info("🧠 TRAIN WAIT chatId={} type={} ex={} net={} symbol={} tf={} trainableSamples={}/{}",
                        chatId, type, exchange, network, symbol, timeframe, trainableCount, minSamples);
            }
            return;
        }

        if (!hasAtLeastTwoClasses(trainable)) {
            log.info("🧠 TRAIN WAIT (need_2_classes) chatId={} type={} ex={} net={} symbol={} tf={} trainableSamples={}",
                    chatId, type, exchange, network, symbol, timeframe, trainableCount);
            return;
        }

        boolean firstTrain = prevTriggeredCount <= 0;
        boolean enoughNewData = (trainableCount - prevTriggeredCount) >= retrainStep;

        if (!firstTrain && !enoughNewData) {
            return;
        }

        if (!lock.compareAndSet(false, true)) {
            return;
        }

        lastAttemptAt.put(contextKey, now);

        try {
            log.info("🧠 AUTO-TRAIN TRIGGER chatId={} type={} ex={} net={} symbol={} tf={} trainableSamples={} minSamples={} prevTriggered={} retrainStep={}",
                    chatId, type, exchange, network, symbol, timeframe, trainableCount, minSamples, prevTriggeredCount, retrainStep);

            MlTrainingResult result = mlTrainingService.trainNow(
                    chatId,
                    type,
                    firstTrain ? "bootstrap_after_samples" : "auto_after_samples"
            );

            if (result == null) {
                log.warn("🧠 AUTO-TRAIN NULL chatId={} type={} ex={} net={} symbol={} tf={}",
                        chatId, type, exchange, network, symbol, timeframe);
                return;
            }

            if (result.ok()) {
                lastTriggeredTrainableCount.put(contextKey, trainableCount);
                log.info("🧠 AUTO-TRAIN DONE chatId={} type={} ex={} net={} symbol={} tf={} applied={} modelKey={} version={} schemaHash={}",
                        chatId,
                        type,
                        exchange,
                        network,
                        symbol,
                        timeframe,
                        result.applied(),
                        result.modelKey(),
                        result.modelVersion(),
                        result.schemaHash());
            } else {
                log.warn("🧠 AUTO-TRAIN SKIP/FAIL chatId={} type={} ex={} net={} symbol={} tf={} error={}",
                        chatId,
                        type,
                        exchange,
                        network,
                        symbol,
                        timeframe,
                        result.error());
            }

        } catch (Exception e) {
            log.warn("🧠 AUTO-TRAIN ERROR chatId={} type={} ex={} net={} symbol={} tf={} err={}",
                    chatId, type, exchange, network, symbol, timeframe, e.toString(), e);
        } finally {
            lock.set(false);
        }
    }

    private long resolveRetrainStep(int minSamples) {
        int half = Math.max(3, minSamples / 2);
        return Math.max(3, Math.min(25, half));
    }

    private boolean hasAtLeastTwoClasses(List<MlSampleEntity> rows) {
        if (rows == null || rows.isEmpty()) return false;

        boolean hasWin = false;
        boolean hasLoss = false;

        for (MlSampleEntity row : rows) {
            if (row == null) continue;
            String label = normUpper(row.getLabel());
            if (label == null) continue;

            if ("WIN".equals(label) || "1".equals(label) || "TRUE".equals(label)) {
                hasWin = true;
            } else if ("LOSS".equals(label) || "0".equals(label) || "FALSE".equals(label)) {
                hasLoss = true;
            }

            if (hasWin && hasLoss) {
                return true;
            }
        }

        return false;
    }

    private void normalizeSample(MlSampleEntity sample) {
        if (sample.getCreatedAt() == null) {
            sample.setCreatedAt(Instant.now());
        }

        if (sample.getSymbol() != null) {
            sample.setSymbol(normUpper(sample.getSymbol()));
        }

        if (sample.getTimeframe() != null) {
            sample.setTimeframe(normLower(sample.getTimeframe()));
        }

        if (sample.getExchange() != null) {
            sample.setExchange(normUpper(sample.getExchange()));
        }

        if (sample.getNetwork() != null) {
            sample.setNetwork(normUpper(sample.getNetwork()));
        }

        if (sample.getLabel() != null) {
            sample.setLabel(normTrim(sample.getLabel()));
        }

        if (sample.getTarget() != null) {
            sample.setTarget(normTrim(sample.getTarget()));
        }
    }

    private static String buildContextKey(StrategyType type,
                                          String exchange,
                                          String network,
                                          String symbol,
                                          String timeframe) {
        return safePart(type != null ? type.name() : null) + ":" +
                safePart(exchange) + ":" +
                safePart(network) + ":" +
                safePart(symbol) + ":" +
                safePart(timeframe);
    }

    private static String safePart(String s) {
        return s == null || s.isBlank() ? "NA" : s;
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