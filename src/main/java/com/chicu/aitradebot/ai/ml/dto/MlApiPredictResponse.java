package com.chicu.aitradebot.ai.ml.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MlApiPredictResponse {
    private boolean enabled;
    private String status;      // ok / down / disabled
    private MlPredictResponse predict;
}
