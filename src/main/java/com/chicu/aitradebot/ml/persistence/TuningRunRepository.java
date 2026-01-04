package com.chicu.aitradebot.ml.persistence;

import com.chicu.aitradebot.common.enums.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TuningRunRepository extends JpaRepository<TuningRunEntity, Long> {

    List<TuningRunEntity> findTop50ByChatIdAndStrategyTypeOrderByCreatedAtDesc(Long chatId, StrategyType strategyType);

    List<TuningRunEntity> findTop10ByChatIdAndStrategyTypeAndSymbolAndTimeframeOrderByCreatedAtDesc(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            String timeframe
    );

    boolean existsByChatIdAndStrategyType(Long chatId, StrategyType strategyType);
}
