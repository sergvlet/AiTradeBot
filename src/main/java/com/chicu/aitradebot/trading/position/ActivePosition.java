package com.chicu.aitradebot.trading.position;

import com.chicu.aitradebot.exchange.enums.OrderSide;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Builder
public class ActivePosition {

    private final Long chatId;
    private final String symbol;

    private final OrderSide side;          // BUY / SELL
    private final BigDecimal entryPrice;
    private final BigDecimal quantity;

    private final BigDecimal takeProfit;
    private final BigDecimal stopLoss;

    private final Instant openedAt;
}
