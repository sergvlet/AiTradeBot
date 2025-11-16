package com.chicu.aitradebot.strategy.smartfusion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SmartFusionStrategySettingsRepository extends JpaRepository<SmartFusionStrategySettings, Long> {
    Optional<SmartFusionStrategySettings> findByChatIdAndSymbol(Long chatId, String symbol);
    boolean existsByChatId(Long chatId);
    Optional<SmartFusionStrategySettings> findByChatId(Long chatId);
    List<SmartFusionStrategySettings> findAllByChatId(Long chatId);
}
