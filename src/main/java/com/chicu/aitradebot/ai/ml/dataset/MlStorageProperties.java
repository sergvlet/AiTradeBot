package com.chicu.aitradebot.ai.ml.dataset;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ml.storage")
public class MlStorageProperties {
    private String modelsDir = "./ml-models";
    private String dataDir = "./ml-data";
}
