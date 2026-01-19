package com.chicu.aitradebot.ai.tuning.score;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.tuning.score")
public class ScorePolicyProperties {

    private double profitWeight = 1.0;
    private double ddWeight = 1.5;
    private double tradesWeight = 0.05;
}
