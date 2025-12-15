package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chart")
public class StrategyTradesApiController {

    private final OrderService orderService;

    // DTO для фронтенда
    public record TradeDto(
            long time,
            String side,
            double price,
            double qty,
            double pnl
    ) {}

    @GetMapping("/trades")
    public List<TradeDto> getTrades(
            @RequestParam long chatId,
            @RequestParam String symbol
    ) {

        log.info("📊 /api/chart/trades → chatId={}, symbol={}", chatId, symbol);

        // 🔥 Используем новый нужный метод
        List<OrderEntity> list = orderService.getOrderEntitiesByChatIdAndSymbol(chatId, symbol);

        return list.stream().map(o -> {
            long time = o.getTimestamp() != null ? o.getTimestamp() : 0L;

            double price = o.getPrice() != null ? o.getPrice().doubleValue() : 0.0;
            double qty = o.getQuantity() != null ? o.getQuantity().doubleValue() : 0.0;

            // 🔥 PnL теперь берём из realizedPnlUsd (оно у тебя в OrderEntity!)
            double pnl = o.getRealizedPnlUsd() != null
                    ? o.getRealizedPnlUsd().doubleValue()
                    : 0.0;

            return new TradeDto(
                    time,
                    o.getSide(),
                    price,
                    qty,
                    pnl
            );
        }).toList();
    }
}
