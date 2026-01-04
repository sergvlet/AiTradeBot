package com.chicu.aitradebot.ml.tuning;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ml.tuning")
public class MlTuningProperties {
    private int initialCandidates = 30;
    private long seed = 42L;

    private int evalMaxCandidates = 10; // <-- новое
}
