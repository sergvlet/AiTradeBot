package com.chicu.aitradebot.strategy.trend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrendStrategySettingsServiceImpl implements TrendStrategySettingsService {

    private final TrendStrategySettingsRepository repo;

    @Override
    @Transactional
    public TrendStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    TrendStrategySettings def = TrendStrategySettings.builder()
                            .chatId(chatId)
                            // значения по умолчанию проставятся в @PrePersist, но можно и тут
                            .emaFastPeriod(9)
                            .emaSlowPeriod(21)
                            .trendThresholdPct(new BigDecimal("0.10"))
                            .cooldownMs(1500)
                            .build();
                    TrendStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки TREND (chatId={}, id={})", chatId, saved.getId());
                    return saved;
                });
    }

    @Override
    @Transactional
    public TrendStrategySettings update(Long chatId, TrendStrategySettings incoming) {

        TrendStrategySettings cur = getOrCreate(chatId);

        // emaFastPeriod >= 1
        if (incoming.getEmaFastPeriod() != null) {
            cur.setEmaFastPeriod(Math.max(1, incoming.getEmaFastPeriod()));
        }

        // emaSlowPeriod >= 1 и >= fast
        if (incoming.getEmaSlowPeriod() != null) {
            int slow = Math.max(1, incoming.getEmaSlowPeriod());
            int fast = cur.getEmaFastPeriod() != null ? cur.getEmaFastPeriod() : 1;
            if (slow < fast) slow = fast;
            cur.setEmaSlowPeriod(slow);
        }

        // trendThresholdPct >= 0
        if (incoming.getTrendThresholdPct() != null) {
            BigDecimal v = incoming.getTrendThresholdPct();
            if (v.signum() < 0) v = BigDecimal.ZERO;
            cur.setTrendThresholdPct(v);
        }

        // cooldownMs >= 0
        if (incoming.getCooldownMs() != null) {
            cur.setCooldownMs(Math.max(0, incoming.getCooldownMs()));
        }

        TrendStrategySettings saved = repo.save(cur);
        log.info("✅ TREND настройки обновлены (chatId={}, id={})", chatId, saved.getId());
        return saved;
    }
}
