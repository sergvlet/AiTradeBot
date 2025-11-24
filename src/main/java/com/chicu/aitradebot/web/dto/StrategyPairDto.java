package com.chicu.aitradebot.web.dto;

import lombok.Data;

@Data
public class StrategyPairDto {
    private String symbol;
    private Double allocatedBalance;
    private Boolean enabled;
    private Object params;
}
