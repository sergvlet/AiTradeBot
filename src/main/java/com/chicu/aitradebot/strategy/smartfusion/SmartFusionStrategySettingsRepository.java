package com.chicu.aitradebot.strategy.smartfusion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SmartFusionStrategySettingsRepository extends JpaRepository<SmartFusionStrategySettings, Long> {

    boolean existsByChatId(Long chatId);

    List<SmartFusionStrategySettings> findAllByChatId(Long chatId);

    /**
     * ❗ НЕ ИСПОЛЬЗУЕМ — при множественных символах он всегда баговый
     *  оставляем для совместимости.
     */
    Optional<SmartFusionStrategySettings> findByChatId(Long chatId);

    /**
     * ✅ ВАЖНО:
     *   Получить ПОСЛЕДНЮЮ запись для chatId (последний выбранный символ).
     */
    @Query("""
            SELECT s
            FROM SmartFusionStrategySettings s
            WHERE s.chatId = :chatId
            ORDER BY s.id DESC
            LIMIT 1
            """)
    Optional<SmartFusionStrategySettings> findLatestByChatId(Long chatId);
}
