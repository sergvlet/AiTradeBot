package com.chicu.aitradebot.ai.ml.sidecar.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictRequestDto {

    private String modelKey;

    // ✅ как в python: featureNames
    private String[] featureNames;

    private double[] x;

    @Builder.Default
    private Map<String, Object> meta = Map.of();
}
