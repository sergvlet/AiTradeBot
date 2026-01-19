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
public class PredictResponseDto {

    private boolean ok;

    /** label: 0/1 */
    private int label;

    /** prob: вероятность класса 1 */
    private double prob;

    /** modelVersion из python */
    private String modelVersion;

    @Builder.Default
    private Map<String, Object> meta = Map.of();

    private String message;

    public static PredictResponseDto fail(String message) {
        return PredictResponseDto.builder()
                .ok(false)
                .label(0)
                .prob(0.0)
                .message(message)
                .build();
    }
}
