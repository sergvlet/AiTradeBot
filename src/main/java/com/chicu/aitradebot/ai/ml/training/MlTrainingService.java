package com.chicu.aitradebot.ai.ml.training;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;

public interface MlTrainingService {
    MlTrainingResult trainNow(Long chatId, StrategyType type, String reason);

    MlTrainingResult trainOnSelectedCandles(Long chatId,
                                            StrategyType type,
                                            String exchangeOverride,
                                            NetworkType networkOverride,
                                            String symbolOverride,
                                            String timeframeOverride,
                                            Integer candlesLimitOverride,
                                            String reason);
}
