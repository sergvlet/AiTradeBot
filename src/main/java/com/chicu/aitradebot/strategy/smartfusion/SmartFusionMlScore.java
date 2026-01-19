// src/main/java/com/chicu/aitradebot/strategy/smartfusion/SmartFusionMlScore.java
package com.chicu.aitradebot.strategy.smartfusion;

import lombok.*;

import java.math.BigDecimal;

/**
 * Результат скоринга ML для SmartFusion.
 * BigDecimal — чтобы было удобно хранить в БД/логах и не терять точность.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmartFusionMlScore {

    /**
     * Общая уверенность модели (можешь трактовать как "насколько далеко от 0.5").
     * [0..1]
     */
    private BigDecimal confidence;

    /**
     * Вероятность BUY. [0..1]
     */
    private BigDecimal probBuy;

    /**
     * Вероятность SELL. [0..1]
     */
    private BigDecimal probSell;

    /**
     * Ключ модели (для трассировки / registry).
     */
    private String modelKey;
}
