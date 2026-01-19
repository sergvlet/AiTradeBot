package com.chicu.aitradebot.strategy.trend_following;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrendFollowingStrategySettingsServiceImpl implements TrendFollowingStrategySettingsService {

    private final TrendFollowingStrategySettingsRepository repo;

    @Override
    @Transactional
    public TrendFollowingStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    TrendFollowingStrategySettings def = TrendFollowingStrategySettings.builder()
                            .chatId(chatId)
                            .build();
                    TrendFollowingStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки TREND_FOLLOWING (chatId={}, id={})", chatId, saved.getId());
                    return saved;
                });
    }

    @Override
    @Transactional
    public TrendFollowingStrategySettings update(Long chatId, TrendFollowingStrategySettings incoming) {

        TrendFollowingStrategySettings cur = getOrCreate(chatId);

        // emaFast >= 1
        if (incoming.getEmaFast() != null) {
            cur.setEmaFast(Math.max(1, incoming.getEmaFast()));
        }

        // emaSlow >= 1, и желательно >= emaFast
        if (incoming.getEmaSlow() != null) {
            int slow = Math.max(1, incoming.getEmaSlow());
            int fast = cur.getEmaFast() != null ? cur.getEmaFast() : 1;
            if (slow < fast) slow = fast;
            cur.setEmaSlow(slow);
        }

        // emaTrend >= 1, и желательно >= emaSlow
        if (incoming.getEmaTrend() != null) {
            int trend = Math.max(1, incoming.getEmaTrend());
            int slow = cur.getEmaSlow() != null ? cur.getEmaSlow() : 1;
            if (trend < slow) trend = slow;
            cur.setEmaTrend(trend);
        }

        // minEmaDiffPct >= 0
        if (incoming.getMinEmaDiffPct() != null) {
            cur.setMinEmaDiffPct(Math.max(0.0, incoming.getMinEmaDiffPct()));
        }

        // minTrendSlopePct может быть и отрицательным (если хочешь фильтровать падение),
        // но по умолчанию ограничим минимумом -100, чтобы не улетало
        if (incoming.getMinTrendSlopePct() != null) {
            double v = incoming.getMinTrendSlopePct();
            if (v < -100.0) v = -100.0;
            cur.setMinTrendSlopePct(v);
        }

        TrendFollowingStrategySettings saved = repo.save(cur);
        log.info("✅ TREND_FOLLOWING настройки обновлены (chatId={}, id={})", chatId, saved.getId());
        return saved;
    }
}
