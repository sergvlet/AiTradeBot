package com.chicu.aitradebot.web.dto;

import lombok.Data;
import java.util.Map;

@Data
public class StrategySettingsDto {
    private Long settingsId;
    private String symbol;
    private Map<String, Object> params;
}
