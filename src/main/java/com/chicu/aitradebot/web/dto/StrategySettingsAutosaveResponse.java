package com.chicu.aitradebot.web.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StrategySettingsAutosaveResponse {
    private boolean ok;
    private String savedAt;     // "15:55:02"
    private String message;     // optional
}
