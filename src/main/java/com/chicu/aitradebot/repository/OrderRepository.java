package com.chicu.aitradebot.repository;

import com.chicu.aitradebot.domain.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    // 🔥 Основной метод, который нужен Dashboard + Chart
    List<OrderEntity> findByChatIdAndSymbolOrderByTimestampAsc(long chatId, String symbol);

    // Если нужны сделки только "закрытые"
    List<OrderEntity> findByChatIdAndSymbolAndStatusOrderByTimestampAsc(long chatId, String symbol, String status);

    // Полезно для последних активных
    List<OrderEntity> findByChatIdAndSymbolAndFilledTrueOrderByTimestampAsc(long chatId, String symbol);
}
