package com.chicu.aitradebot.web.facade;

import java.math.BigDecimal;
import java.util.List;

public interface WebOrderFacade {

    OrderResult marketBuy(Long chatId, String symbol, BigDecimal amount);

    OrderResult marketSell(Long chatId, String symbol, BigDecimal amount);

    OrderResult cancel(Long chatId, String symbol, long orderId);

    List<OrderInfo> list(Long chatId, String symbol);

    // =============================================================
    // DTO
    // =============================================================

    record OrderInfo(
            long orderId,
            String symbol,
            String side,
            String status,
            BigDecimal executedQty,
            BigDecimal price
    ) {}

    record OrderResult(
            boolean success,
            String message,
            Long orderId
    ) {}
}
