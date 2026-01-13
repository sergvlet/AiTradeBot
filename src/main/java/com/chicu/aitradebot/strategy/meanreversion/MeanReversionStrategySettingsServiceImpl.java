package com.chicu.aitradebot.strategy.meanreversion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeanReversionStrategySettingsServiceImpl
        implements MeanReversionStrategySettingsService {

    private final MeanReversionStrategySettingsRepository repo;

    @Override
    public MeanReversionStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    MeanReversionStrategySettings def =
                            MeanReversionStrategySettings.builder()
                                    .chatId(chatId)
                                    .build();
                    MeanReversionStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки MEAN_REVERSION (chatId={})", chatId);
                    return saved;
                });
    }
}
