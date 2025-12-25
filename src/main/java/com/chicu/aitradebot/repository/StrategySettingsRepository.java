package com.chicu.aitradebot.repository;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StrategySettingsRepository extends JpaRepository<StrategySettings, Long> {

    List<StrategySettings> findByChatId(long chatId);

    List<StrategySettings> findByChatIdAndType(long chatId, StrategyType type);

    // ✅ КЛЮЧЕВОЙ МЕТОД (истина для UI)
    Optional<StrategySettings> findTopByChatIdAndTypeAndExchangeNameAndNetworkTypeOrderByIdDesc(
            long chatId,
            StrategyType type,
            String exchangeName,
            NetworkType networkType
    );
}
