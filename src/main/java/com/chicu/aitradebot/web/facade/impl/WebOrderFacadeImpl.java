package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.web.facade.WebOrderFacade;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WebOrderFacadeImpl implements WebOrderFacade {

    private final AiStrategyOrchestrator orchestrator;

    @Override
    public OrderResult marketBuy(Long chatId, String symbol, java.math.BigDecimal amount) {
        var r = orchestrator.marketBuy(chatId, symbol, amount);
        return new OrderResult(r.success(), r.message(), r.orderId());
    }

    @Override
    public OrderResult marketSell(Long chatId, String symbol, java.math.BigDecimal amount) {
        var r = orchestrator.marketSell(chatId, symbol, amount);
        return new OrderResult(r.success(), r.message(), r.orderId());
    }

    @Override
    public OrderResult cancel(Long chatId, String symbol, long orderId) {
        boolean ok = orchestrator.cancelOrder(chatId, orderId);
        return new OrderResult(ok, ok ? "Отменён" : "Ошибка отмены", ok ? orderId : null);
    }

    @Override
    public List<OrderInfo> list(Long chatId, String symbol) {
        return orchestrator.listOrders(chatId, symbol).stream()
                .map(o -> new OrderInfo(
                        o.id(),          // Long
                        o.symbol(),      // String
                        o.side(),        // String
                        o.status(),      // String
                        o.quantity(),    // BigDecimal (аналог executedQty)
                        o.price()        // BigDecimal
                ))
                .toList();
    }
}
