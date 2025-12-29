package com.chicu.aitradebot.repository;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StrategySettingsRepository extends JpaRepository<StrategySettings, Long> {

    // =====================================================================
    // БАЗОВЫЕ
    // =====================================================================
    List<StrategySettings> findByChatId(long chatId);

    List<StrategySettings> findByChatIdAndType(long chatId, StrategyType type);

    // =====================================================================
    // FALLBACK (когда exchange / network ещё не выбраны)
    // используется ТОЛЬКО для чтения
    // =====================================================================
    Optional<StrategySettings> findTopByChatIdAndTypeOrderByUpdatedAtDescIdDesc(
            long chatId,
            StrategyType type
    );

    // =====================================================================
    // КЛЮЧЕВОЙ МЕТОД (ИСТИНА)
    // UI / Live / Runner / Toggle
    // =====================================================================
    Optional<StrategySettings> findTopByChatIdAndTypeAndExchangeNameAndNetworkTypeOrderByUpdatedAtDescIdDesc(
            long chatId,
            StrategyType type,
            String exchangeName,
            NetworkType networkType
    );

}
