package com.chicu.aitradebot.ai.ml;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ml.train")
public class MlTrainProperties {
    private boolean enabled = true;
    private int minSamples = 500;
    private int lookbackDays = 14;
    private int cooldownMinutes = 30;
    private boolean autoApply = true;
}
