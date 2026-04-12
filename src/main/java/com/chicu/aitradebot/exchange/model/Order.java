package com.chicu.aitradebot.exchange.model;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO ордера между стратегиями, сервисами и UI.
 * Не является JPA Entity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    /** id ордера в локальной БД */
    private Long id;

    /** id ордера на бирже */
    private String orderId;

    private String exchangeTradeId;
    private String clientOrderId;
    private String positionUid;
    private String correlationId;
    private String intent;

    /** chatId пользователя */
    private Long chatId;

    /** Торговая пара, например BTCUSDT */
    private String symbol;

    /** BUY / SELL */
    private String side;

    /** MARKET / LIMIT / OCO */
    private String type;

    @Deprecated
    private BigDecimal qty;

    private BigDecimal quantity;

    /** Цена ордера или фактическая цена исполнения */
    private BigDecimal price;

    /** Средняя цена исполнения */
    private BigDecimal avgPrice;

    /** Фактически исполненное количество */
    private BigDecimal executedQty;

    private BigDecimal executedQuoteQty;
    private BigDecimal requestedQty;
    private BigDecimal requestedPrice;

    /** Реальная комиссия исполнения */
    private BigDecimal feeTotal;
    private String feeAsset;

    /** Статус ордера */
    private String status;

    @Deprecated
    private Long timestamp;

    private Long time;

    private boolean filled;

    private StrategyType strategyType;
    private String exchangeName;
    private NetworkType networkType;

    public BigDecimal getQuantity() {
        return quantity != null ? quantity : qty;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
        this.qty = quantity;
    }

    public Long getTime() {
        return time != null ? time : timestamp;
    }

    public void setTime(Long time) {
        this.time = time;
        this.timestamp = time;
    }

    public String getSideUpper() {
        return side != null ? side.toUpperCase() : null;
    }

    public String getTypeUpper() {
        return type != null ? type.toUpperCase() : null;
    }

    public static Order market(Long chatId,
                               String symbol,
                               String side,
                               BigDecimal quantity,
                               BigDecimal executionPrice,
                               StrategyType strategyType) {
        Order order = new Order();
        order.chatId = chatId;
        order.symbol = symbol;
        order.side = side != null ? side.toUpperCase() : null;
        order.type = "MARKET";
        order.setQuantity(quantity);
        order.requestedQty = quantity;
        order.requestedPrice = executionPrice;
        order.price = executionPrice;
        order.avgPrice = executionPrice;
        order.executedQty = quantity;
        order.executedQuoteQty = executionPrice != null && quantity != null ? executionPrice.multiply(quantity) : null;
        order.status = "FILLED";
        order.filled = true;
        order.time = System.currentTimeMillis();
        order.strategyType = strategyType;
        return order;
    }
}
