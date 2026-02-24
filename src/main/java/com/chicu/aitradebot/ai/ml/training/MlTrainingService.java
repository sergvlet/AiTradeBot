package com.chicu.aitradebot.ai.ml.training;

import com.chicu.aitradebot.common.enums.StrategyType;

public interface MlTrainingService {
    MlTrainingResult trainNow(Long chatId, StrategyType type, String reason);
}
