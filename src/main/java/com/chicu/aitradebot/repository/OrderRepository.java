package com.chicu.aitradebot.repository;

import com.chicu.aitradebot.domain.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    // 🔥 Основной метод, который нужен Dashboard + Chart
    List<OrderEntity> findByChatIdAndSymbolOrderByTimestampAsc(long chatId, String symbol);

    // Открытые ордера для cancel / getOpenOrders
    List<OrderEntity> findByChatIdAndSymbolAndStatusIn(Long chatId,
                                                       String symbol,
                                                       Collection<String> statuses);
}
