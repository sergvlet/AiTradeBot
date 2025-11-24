package com.chicu.aitradebot.web.dto;

import lombok.Data;

@Data
public class StrategyDashboardDto {
    private Object candles;
    private Object trades;
    private Object runtime;
}
