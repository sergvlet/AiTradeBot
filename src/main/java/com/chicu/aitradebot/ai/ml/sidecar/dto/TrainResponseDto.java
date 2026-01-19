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
public class TrainResponseDto {

    private boolean ok;

    private String modelPath;
    private String modelVersion;

    @Builder.Default
    private Map<String, Object> metrics = Map.of();

    private String message;
}
