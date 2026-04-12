package com.chicu.aitradebot.ai.ml.policy;

import lombok.Data;

@Data
public class StrategyMlOverride {
    private Boolean enabled;
    private Boolean failOpen;
    private Double minProba;
}
