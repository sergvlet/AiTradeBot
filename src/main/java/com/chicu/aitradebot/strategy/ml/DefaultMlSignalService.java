// src/main/java/com/chicu/aitradebot/strategy/ml/DefaultMlSignalService.java
package com.chicu.aitradebot.strategy.ml;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Заглушка, чтобы Spring поднялся и стратегии работали.
 * Позже заменишь на реальный ML-инференс.
 */
@Slf4j
@Service
public class DefaultMlSignalService implements MlSignalService {

    @Override
    public MlPrediction predict(Long chatId, String symbol, String timeframe, MlFeatures features) {

        // супер-бережная заглушка: небольшие случайные вероятности
        double pBuy  = ThreadLocalRandom.current().nextDouble(0.45, 0.55);
        double pSell = ThreadLocalRandom.current().nextDouble(0.45, 0.55);

        // confidence можно просто как "насколько отличие от 0.5"
        double conf = Math.min(1.0, Math.max(0.0, Math.abs(pBuy - 0.5) * 2.0));

        return new MlPrediction(pBuy, pSell, conf, "stub");
    }
}
