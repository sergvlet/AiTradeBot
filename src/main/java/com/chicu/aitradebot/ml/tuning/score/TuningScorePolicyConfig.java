package com.chicu.aitradebot.ml.tuning.score;

import com.chicu.aitradebot.ml.tuning.eval.BacktestMetrics;
import com.chicu.aitradebot.ml.tuning.scalping.ScalpingScorePolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;

@Configuration
public class TuningScorePolicyConfig {

    @Bean
    @Primary
    public TuningScorePolicy tuningScorePolicy(ScalpingScorePolicy scalpingScorePolicy) {

        return (TuningScorePolicy) Proxy.newProxyInstance(
                TuningScorePolicy.class.getClassLoader(),
                new Class<?>[]{TuningScorePolicy.class},
                (proxy, method, args) -> {

                    // если кто-то вызовет toString/hashCode/equals — обработаем нормально
                    String name = method.getName();
                    if ("toString".equals(name)) return "TuningScorePolicyProxy";
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) return proxy == (args != null && args.length > 0 ? args[0] : null);

                    // Основной кейс: метод возвращает BigDecimal (скор)
                    if (method.getReturnType() == BigDecimal.class) {
                        BacktestMetrics m = extractMetrics(args);
                        if (m == null) {
                            return BigDecimal.valueOf(-1_000_000);
                        }
                        return scalpingScorePolicy.score(m);
                    }

                    // если вдруг интерфейс возвращает double/Double — тоже поддержим
                    if (method.getReturnType() == double.class || method.getReturnType() == Double.class) {
                        BacktestMetrics m = extractMetrics(args);
                        BigDecimal s = (m == null) ? BigDecimal.valueOf(-1_000_000) : scalpingScorePolicy.score(m);
                        return s.doubleValue();
                    }

                    // прочее — безопасный дефолт
                    return null;
                }
        );
    }

    private static BacktestMetrics extractMetrics(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof BacktestMetrics m) {
                return m;
            }
        }
        return null;
    }
}
