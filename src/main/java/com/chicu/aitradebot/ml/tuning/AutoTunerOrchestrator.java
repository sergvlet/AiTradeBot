package com.chicu.aitradebot.ml.tuning;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AutoTunerOrchestrator {

    private final Map<StrategyType, StrategyAutoTuner> tuners = new EnumMap<>(StrategyType.class);

    public AutoTunerOrchestrator(List<StrategyAutoTuner> tunerList) {
        for (StrategyAutoTuner t : tunerList) {
            StrategyType type = t.getStrategyType();
            if (type == null) continue;

            StrategyAutoTuner prev = tuners.put(type, t);
            if (prev != null) {
                log.warn("⚠️ Найдено 2 тюнера для {}: {} и {}. Использую последний.",
                        type, prev.getClass().getSimpleName(), t.getClass().getSimpleName());
            }
        }

        log.info("🧠 ML AutoTunerOrchestrator поднят. Тюнеров зарегистрировано: {}", tuners.size());
    }

    public TuningResult tune(TuningRequest request) {
        if (request == null || request.strategyType() == null) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("strategyType не задан")
                    .build();
        }

        StrategyAutoTuner tuner = tuners.get(request.strategyType());
        if (tuner == null) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("Тюнер для " + request.strategyType() + " не зарегистрирован")
                    .build();
        }

        return tuner.tune(request);
    }
}
