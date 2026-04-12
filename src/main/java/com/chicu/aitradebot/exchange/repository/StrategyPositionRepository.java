package com.chicu.aitradebot.exchange.repository;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategyPositionEntity;
import com.chicu.aitradebot.domain.enums.StrategyPositionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StrategyPositionRepository extends JpaRepository<StrategyPositionEntity, Long> {

    Optional<StrategyPositionEntity> findByPositionUid(String positionUid);

    Optional<StrategyPositionEntity> findFirstByChatIdAndStrategyTypeAndExchangeNameAndNetworkTypeAndSymbolAndStatusInOrderByOpenedAtDesc(
            Long chatId,
            StrategyType strategyType,
            String exchangeName,
            NetworkType networkType,
            String symbol,
            Collection<StrategyPositionStatus> statuses
    );

    List<StrategyPositionEntity> findByChatIdAndStrategyTypeAndExchangeNameAndNetworkTypeAndSymbolOrderByOpenedAtDesc(
            Long chatId,
            StrategyType strategyType,
            String exchangeName,
            NetworkType networkType,
            String symbol
    );
}
