// src/main/java/com/chicu/aitradebot/web/advanced/renderers/MlClassificationAdvancedRenderer.java
package com.chicu.aitradebot.web.advanced.renderers;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.ml.MlClassificationStrategySettings;
import org.springframework.stereotype.Component;

@Component
public class MlClassificationAdvancedRenderer extends AbstractEntityAdvancedRenderer<MlClassificationStrategySettings> {

    public MlClassificationAdvancedRenderer() {
        super(MlClassificationStrategySettings.class);
    }

    @Override
    public StrategyType supports() {
        return StrategyType.ML_CLASSIFICATION;
    }
}
