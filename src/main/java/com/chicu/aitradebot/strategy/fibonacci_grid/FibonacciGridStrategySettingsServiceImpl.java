package com.chicu.aitradebot.strategy.fibonacci_grid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class FibonacciGridStrategySettingsServiceImpl implements FibonacciGridStrategySettingsService {

    private static final BigDecimal DEF_DISTANCE_PCT = new BigDecimal("0.5");
    private static final BigDecimal DEF_TP_PCT = new BigDecimal("0.80");
    private static final BigDecimal DEF_SL_PCT = new BigDecimal("1.20");

    private final FibonacciGridStrategySettingsRepository repo;

    @Override
    public FibonacciGridStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    FibonacciGridStrategySettings def = FibonacciGridStrategySettings.builder()
                            .chatId(chatId)
                            .gridLevels(6)
                            .distancePct(DEF_DISTANCE_PCT)
                            .takeProfitPct(DEF_TP_PCT)
                            .stopLossPct(DEF_SL_PCT)
                            .build();
                    FibonacciGridStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки FIBONACCI_GRID (chatId={})", chatId);
                    return saved;
                });
    }

    @Override
    public FibonacciGridStrategySettings update(Long chatId, FibonacciGridStrategySettings incoming) {
        FibonacciGridStrategySettings cur = getOrCreate(chatId);

        if (incoming.getGridLevels() != null) cur.setGridLevels(incoming.getGridLevels());
        if (incoming.getDistancePct() != null) cur.setDistancePct(incoming.getDistancePct());
        if (incoming.getTakeProfitPct() != null) cur.setTakeProfitPct(incoming.getTakeProfitPct());
        if (incoming.getStopLossPct() != null) cur.setStopLossPct(incoming.getStopLossPct());

        // orderVolume допускаем null: тогда объём берётся исполнителем из общих настроек
        cur.setOrderVolume(incoming.getOrderVolume());

        if (cur.getGridLevels() == null || cur.getGridLevels() < 1) cur.setGridLevels(1);
        if (cur.getDistancePct() == null || cur.getDistancePct().signum() <= 0) cur.setDistancePct(DEF_DISTANCE_PCT);
        if (cur.getTakeProfitPct() == null || cur.getTakeProfitPct().signum() <= 0) cur.setTakeProfitPct(DEF_TP_PCT);
        if (cur.getStopLossPct() == null || cur.getStopLossPct().signum() <= 0) cur.setStopLossPct(DEF_SL_PCT);

        FibonacciGridStrategySettings saved = repo.save(cur);
        log.info("✅ FIBONACCI_GRID settings saved (chatId={})", chatId);
        return saved;
    }
}
