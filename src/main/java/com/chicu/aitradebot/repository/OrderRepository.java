package com.chicu.aitradebot.repository;

import com.chicu.aitradebot.domain.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    // 🔥 Основной метод, который нужен Dashboard + Chart
    List<OrderEntity> findByChatIdAndSymbolOrderByTimestampAsc(long chatId, String symbol);

    // Если нужны сделки только "закрытые"
    List<OrderEntity> findByChatIdAndSymbolAndStatusOrderByTimestampAsc(long chatId, String symbol, String status);

    // Полезно для последних активных
    List<OrderEntity> findByChatIdAndSymbolAndFilledTrueOrderByTimestampAsc(long chatId, String symbol);

    // История по чату и символу (для графиков / стратегий)
    List<OrderEntity> findByChatIdAndSymbolOrderByTimestampAsc(Long chatId, String symbol);

    // Ордера по чату и символу без сортировки (на всякий случай)
    List<OrderEntity> findByChatIdAndSymbol(Long chatId, String symbol);

    // Открытые ордера для cancel / getOpenOrders
    List<OrderEntity> findByChatIdAndSymbolAndStatusIn(Long chatId,
                                                       String symbol,
                                                       Collection<String> statuses);
}
