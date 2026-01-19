package com.chicu.aitradebot.strategy.dca;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DcaStrategySettingsRepository extends JpaRepository<DcaStrategySettings, Long> {

    Optional<DcaStrategySettings> findTopByChatIdOrderByIdDesc(Long chatId);
}
