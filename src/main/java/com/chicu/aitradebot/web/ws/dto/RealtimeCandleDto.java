package com.chicu.aitradebot.web.ws.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RealtimeCandleDto {
    private long time;   // ms
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
}
