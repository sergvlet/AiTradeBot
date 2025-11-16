package com.chicu.aitradebot.web.ws.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RealtimeTradeDto {

    private Long id;
    private Long chatId;
    private String symbol;
    private String side;         // BUY / SELL
    private double price;
    private double qty;
    private String status;       // FILLED / OPEN / ...
    private String strategyType;
    private Long time;           // ms (время входа)

    // Доп. поля для тултипа:
    private Double exitPrice;
    private Long exitTime;
    private Double tpPrice;
    private Double slPrice;
    private Boolean tpHit;
    private Boolean slHit;
    private Double pnlUsd;
    private Double pnlPct;
    private String entryReason;
    private String exitReason;
    private Double mlConfidence;
}
