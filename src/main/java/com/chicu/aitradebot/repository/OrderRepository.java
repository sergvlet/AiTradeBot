package com.chicu.aitradebot.repository;

import com.chicu.aitradebot.domain.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    // основной метод для Dashboard + Chart
    List<OrderEntity> findByChatIdAndSymbolOrderByTimestampAsc(long chatId, String symbol);

    // открытые ордера для cancel / getOpenOrders
    List<OrderEntity> findByChatIdAndSymbolAndStatusIn(Long chatId,
                                                       String symbol,
                                                       Collection<String> statuses);

    // точный runtime-контекст для восстановления позиции после рестарта
    List<OrderEntity> findByChatIdAndStrategyTypeAndSymbolAndExchangeNameAndNetworkTypeOrderByTimestampAsc(
            Long chatId,
            String strategyType,
            String symbol,
            String exchangeName,
            String networkType
    );

    // legacy fallback: старые строки без exchange/network
    List<OrderEntity> findByChatIdAndStrategyTypeAndSymbolOrderByTimestampAsc(
            Long chatId,
            String strategyType,
            String symbol
    );
}