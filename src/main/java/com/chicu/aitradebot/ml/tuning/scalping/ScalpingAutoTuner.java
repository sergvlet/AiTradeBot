package com.chicu.aitradebot.ml.tuning.scalping;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.ml.tuning.MlTuningProperties;
import com.chicu.aitradebot.ml.tuning.StrategyAutoTuner;
import com.chicu.aitradebot.ml.tuning.TuningCandidate;
import com.chicu.aitradebot.ml.tuning.TuningRequest;
import com.chicu.aitradebot.ml.tuning.TuningResult;
import com.chicu.aitradebot.ml.tuning.candidates.CandidateFilter;
import com.chicu.aitradebot.ml.tuning.candidates.CandidateGenerator;
import com.chicu.aitradebot.ml.tuning.guard.GuardDecision;
import com.chicu.aitradebot.ml.tuning.guard.TuningGuard;
import com.chicu.aitradebot.ml.tuning.space.ParamSpaceItem;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettings;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettingsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScalpingAutoTuner implements StrategyAutoTuner {

    private final ScalpingParamSpaceService paramSpaceService;
    private final CandidateGenerator candidateGenerator;
    private final CandidateFilter candidateFilter;
    private final MlTuningProperties props;

    private final ScalpingStrategySettingsService scalpingSettingsService;
    private final StrategySettingsService strategySettingsService;
    private final ScalpingCandidateMapper mapper;

    @Qualifier("scalpingTuningGuard")
    private final TuningGuard scalpingTuningGuard;

    @PersistenceContext
    private final EntityManager em;

    @Override
    public StrategyType getStrategyType() {
        return StrategyType.SCALPING;
    }

    @Override
    public TuningResult tune(TuningRequest request) {

        if (request == null || request.chatId() == null) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("Некорректный запрос: request/chatId is null")
                    .build();
        }

        Long chatId = request.chatId();

        // 1) guard частоты
        GuardDecision freq = scalpingTuningGuard.checkFrequency(chatId);
        if (!freq.allowed()) {
            log.info("⏳ SCALPING tuning blocked (chatId={}): {}", chatId, freq.reason());
            return TuningResult.builder()
                    .applied(false)
                    .reason("Guard: " + freq.reason())
                    .build();
        }

        // 2) пространство
        Map<String, ParamSpaceItem> space = paramSpaceService.loadEnabledSpace();
        if (space == null || space.isEmpty()) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("ParamSpace пустой: нет enabled параметров в ml_tuning_space для SCALPING")
                    .build();
        }

        // 3) текущие настройки (scalping + common strategy_settings)
        ScalpingStrategySettings scalping = scalpingSettingsService.getOrCreate(chatId);

        StrategySettings latest = loadLatestStrategySettings(chatId, StrategyType.SCALPING);
        if (latest == null || latest.getExchangeName() == null || latest.getNetworkType() == null) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("Нет StrategySettings(exchangeName/networkType) для SCALPING. " +
                            "Сначала создай/сохрани настройки стратегии (биржа/сеть/символ/таймфрейм).")
                    .build();
        }

        StrategySettings common = strategySettingsService.getOrCreate(
                chatId,
                StrategyType.SCALPING,
                latest.getExchangeName(),
                latest.getNetworkType()
        );

        Map<String, Object> currentParams = mapper.toParamMap(scalping, common);

        long seed = request.seed() != null ? request.seed() : props.getSeed();
        int n = props.getInitialCandidates();

        List<TuningCandidate> generated = candidateGenerator.generate(space, n, seed);
        if (generated == null || generated.isEmpty()) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("CandidateGenerator вернул пустой список")
                    .oldParams(currentParams)
                    .build();
        }

        List<TuningCandidate> filtered = candidateFilter.filter(chatId, currentParams, generated, scalpingTuningGuard);

        log.info("🧠 SCALPING tuning (chatId={}): space={}, generated={}, filtered={}, seed={}",
                chatId, space.size(), generated.size(), filtered.size(), seed);

        if (filtered != null && !filtered.isEmpty()) {
            log.info("🧪 Candidate[0] (filtered): {}", filtered.get(0).params());
        }

        return TuningResult.builder()
                .applied(false)
                .reason("Кандидаты сгенерированы и отфильтрованы Guard. Следующий шаг: BacktestPort->Metrics->score.")
                .oldParams(currentParams)
                .build();
    }

    private StrategySettings loadLatestStrategySettings(Long chatId, StrategyType type) {
        return em.createQuery(
                        "select s from StrategySettings s " +
                        "where s.chatId = :chatId and s.type = :type " +
                        "order by s.id desc",
                        StrategySettings.class
                )
                .setParameter("chatId", chatId)
                .setParameter("type", type)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }
}
