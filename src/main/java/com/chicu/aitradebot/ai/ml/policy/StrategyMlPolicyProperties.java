package com.chicu.aitradebot.ai.ml.policy;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "strategy.ml")
public class StrategyMlPolicyProperties {

    /**
     * ML-гейт включён по умолчанию для всех стратегий
     * (если глобально ml.enabled=true).
     */
    private boolean enabled = true;

    /**
     * Если ML упал/недоступен:
     * true  -> пропускаем сигнал (fail-open)
     * false -> режем в HOLD (fail-closed)
     */
    private boolean failOpen = true;

    /**
     * Минимальная вероятность, чтобы подтвердить BUY/SELL.
     */
    private double minProba = 0.60;

    /**
     * Переопределения по стратегиям.
     * В properties пишется как:
     * strategy.ml.overrides.WINDOW_SCALPING.enabled=true
     * strategy.ml.overrides.WINDOW_SCALPING.min-proba=0.65
     */
    private Map<StrategyType, StrategyMlOverride> overrides = new HashMap<>();

    public StrategyMlResolved resolve(StrategyType type) {
        StrategyMlOverride o = overrides.get(type);
        boolean e = (o != null && o.getEnabled() != null) ? o.getEnabled() : enabled;
        boolean f = (o != null && o.getFailOpen() != null) ? o.getFailOpen() : failOpen;
        double  p = (o != null && o.getMinProba() != null) ? o.getMinProba() : minProba;
        return new StrategyMlResolved(e, f, p);
    }
}
