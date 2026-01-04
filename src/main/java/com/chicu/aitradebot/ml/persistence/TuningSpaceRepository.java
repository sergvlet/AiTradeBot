package com.chicu.aitradebot.ml.persistence;

import com.chicu.aitradebot.common.enums.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TuningSpaceRepository extends JpaRepository<TuningSpaceEntity, Long> {

    List<TuningSpaceEntity> findByStrategyTypeAndEnabledTrueOrderByParamNameAsc(StrategyType strategyType);

    Optional<TuningSpaceEntity> findByStrategyTypeAndParamName(StrategyType strategyType, String paramName);
}
