package com.chicu.aitradebot.ml.tuning.scalping;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.ml.persistence.TuningSpaceEntity;
import com.chicu.aitradebot.ml.persistence.TuningSpaceRepository;
import com.chicu.aitradebot.ml.tuning.space.ParamSpaceItem;
import com.chicu.aitradebot.ml.tuning.space.ParamSpaceService;
import com.chicu.aitradebot.ml.tuning.space.ParamSpaceValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScalpingParamSpaceService implements ParamSpaceService {

    private final TuningSpaceRepository repo;

    @Override
    public StrategyType getStrategyType() {
        return StrategyType.SCALPING;
    }

    @Override
    public Map<String, ParamSpaceItem> loadEnabledSpace() {
        List<TuningSpaceEntity> rows = repo.findByStrategyTypeAndEnabledTrueOrderByParamNameAsc(getStrategyType());

        Map<String, ParamSpaceItem> space = new LinkedHashMap<>();
        for (TuningSpaceEntity e : rows) {
            ParamSpaceValidator.validateOrThrow(e);

            String name = e.normalizedParamName();
            space.put(name, ParamSpaceItem.builder()
                    .name(name)
                    .type(e.getValueType())
                    .min(e.getMinValue())
                    .max(e.getMaxValue())
                    .step(e.getStepValue())
                    .build());
        }

        log.info("🧩 ParamSpace SCALPING: загружено параметров = {}", space.size());
        return space;
    }
}
