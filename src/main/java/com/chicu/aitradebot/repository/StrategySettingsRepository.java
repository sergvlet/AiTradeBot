package com.chicu.aitradebot.repository;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StrategySettingsRepository extends JpaRepository<StrategySettings, Long> {

    List<StrategySettings> findByType(StrategyType type);

    List<StrategySettings> findByActiveTrue();

    List<StrategySettings> findBySymbolIgnoreCase(String symbol);
}
