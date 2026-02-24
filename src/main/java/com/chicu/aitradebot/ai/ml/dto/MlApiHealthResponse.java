package com.chicu.aitradebot.ai.ml.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MlApiHealthResponse {
    private boolean enabled;
    private String baseUrl;
    private String status;      // ok / down / disabled
    private MlHealthResponse mlHealth;
}
