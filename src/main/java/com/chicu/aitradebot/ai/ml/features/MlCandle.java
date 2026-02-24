package com.chicu.aitradebot.ai.ml.features;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MlCandle {
    private long ts;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
}
