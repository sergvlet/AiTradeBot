package com.chicu.aitradebot.repository;

import com.chicu.aitradebot.domain.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    List<OrderEntity> findByChatIdAndSymbolOrderByTimestampAsc(long chatId, String symbol);

    List<OrderEntity> findByChatIdAndSymbolAndStatusIn(Long chatId,
                                                       String symbol,
                                                       Collection<String> statuses);

    List<OrderEntity> findByChatIdAndStrategyTypeAndSymbolAndExchangeNameAndNetworkTypeOrderByTimestampAsc(
            Long chatId,
            String strategyType,
            String symbol,
            String exchangeName,
            String networkType
    );

    List<OrderEntity> findByChatIdAndStrategyTypeAndSymbolOrderByTimestampAsc(
            Long chatId,
            String strategyType,
            String symbol
    );
}