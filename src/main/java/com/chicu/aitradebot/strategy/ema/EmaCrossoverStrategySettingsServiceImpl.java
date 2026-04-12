package com.chicu.aitradebot.strategy.ema;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmaCrossoverStrategySettingsServiceImpl implements EmaCrossoverStrategySettingsService {

    private static final int MIN_FAST = 1;
    private static final int MAX_FAST = 300;

    private static final int MIN_SLOW = 2;
    private static final int MAX_SLOW = 600;

    private static final int MIN_CONFIRM = 1;
    private static final int MAX_CONFIRM = 10;

    private static final double MIN_SPREAD = 0.0d;
    private static final double MAX_SPREAD = 100.0d;

    private static final BigDecimal DEFAULT_TP = new BigDecimal("1.20");
    private static final BigDecimal DEFAULT_SL = new BigDecimal("0.80");
    private static final BigDecimal MIN_PCT = new BigDecimal("0.01");
    private static final BigDecimal MAX_PCT = new BigDecimal("50.00");

    private final EmaCrossoverStrategySettingsRepository repo;

    @Override
    @Transactional
    public EmaCrossoverStrategySettings getOrCreate(Long chatId) {
        validateChatId(chatId);

        EmaCrossoverStrategySettings existing = repo.findTopByChatIdOrderByUpdatedAtDesc(chatId).orElse(null);
        if (existing != null) {
            normalize(existing);
            return existing;
        }

        try {
            EmaCrossoverStrategySettings created = repo.saveAndFlush(
                    EmaCrossoverStrategySettings.builder()
                            .chatId(chatId)
                            .build()
            );
            log.info("🆕 EMA_CROSSOVER settings created (chatId={}, id={})", chatId, created.getId());
            return created;
        } catch (DataIntegrityViolationException dup) {
            return repo.findTopByChatIdOrderByUpdatedAtDesc(chatId).orElseThrow(() -> dup);
        }
    }

    @Override
    @Transactional
    public EmaCrossoverStrategySettings update(Long chatId, EmaCrossoverStrategySettings incoming) {
        validateChatId(chatId);
        if (incoming == null) {
            throw new IllegalArgumentException("incoming is null");
        }

        EmaCrossoverStrategySettings cur = getOrCreate(chatId);

        if (incoming.getEmaFast() != null) {
            cur.setEmaFast(clampInt(incoming.getEmaFast(), MIN_FAST, MAX_FAST));
        }

        if (incoming.getEmaSlow() != null) {
            cur.setEmaSlow(clampInt(incoming.getEmaSlow(), MIN_SLOW, MAX_SLOW));
        }

        if (incoming.getConfirmBars() != null) {
            cur.setConfirmBars(clampInt(incoming.getConfirmBars(), MIN_CONFIRM, MAX_CONFIRM));
        }

        if (incoming.getMaxSpreadPct() != null) {
            cur.setMaxSpreadPct(clampDouble(incoming.getMaxSpreadPct(), MIN_SPREAD, MAX_SPREAD));
        }

        if (incoming.getTakeProfitPct() != null) {
            cur.setTakeProfitPct(sanitizePct(incoming.getTakeProfitPct(), DEFAULT_TP));
        }

        if (incoming.getStopLossPct() != null) {
            cur.setStopLossPct(sanitizePct(incoming.getStopLossPct(), DEFAULT_SL));
        }

        normalize(cur);

        EmaCrossoverStrategySettings saved = repo.saveAndFlush(cur);
        log.info("✅ EMA_CROSSOVER settings updated (chatId={}, id={}, emaFast={}, emaSlow={}, confirmBars={}, maxSpreadPct={}, tpPct={}, slPct={})",
                chatId,
                saved.getId(),
                saved.getEmaFast(),
                saved.getEmaSlow(),
                saved.getConfirmBars(),
                saved.getMaxSpreadPct(),
                saved.getTakeProfitPct(),
                saved.getStopLossPct());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Long getVersion(Long chatId) {
        validateChatId(chatId);
        return repo.findTopByChatIdOrderByUpdatedAtDesc(chatId)
                .map(EmaCrossoverStrategySettings::getVersion)
                .map(Integer::longValue)
                .orElse(0L);
    }

    private static void validateChatId(Long chatId) {
        if (chatId == null || chatId <= 0) {
            throw new IllegalArgumentException("chatId must be positive");
        }
    }

    private static void normalize(EmaCrossoverStrategySettings s) {
        if (s == null) return;

        int fast = s.getEmaFast() != null ? s.getEmaFast() : 9;
        int slow = s.getEmaSlow() != null ? s.getEmaSlow() : 21;
        int confirmBars = s.getConfirmBars() != null ? s.getConfirmBars() : 1;
        double spread = s.getMaxSpreadPct() != null ? s.getMaxSpreadPct() : 0.08d;
        BigDecimal tp = sanitizePct(s.getTakeProfitPct(), DEFAULT_TP);
        BigDecimal sl = sanitizePct(s.getStopLossPct(), DEFAULT_SL);

        fast = clampInt(fast, MIN_FAST, MAX_FAST);
        slow = clampInt(slow, MIN_SLOW, MAX_SLOW);
        if (slow <= fast) slow = Math.min(MAX_SLOW, fast + 1);

        confirmBars = clampInt(confirmBars, MIN_CONFIRM, MAX_CONFIRM);
        spread = clampDouble(spread, MIN_SPREAD, MAX_SPREAD);

        s.setEmaFast(fast);
        s.setEmaSlow(slow);
        s.setConfirmBars(confirmBars);
        s.setMaxSpreadPct(spread);
        s.setTakeProfitPct(tp);
        s.setStopLossPct(sl);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static BigDecimal sanitizePct(BigDecimal value, BigDecimal def) {
        BigDecimal pct = value != null ? value : def;
        if (pct.signum() <= 0) pct = def;
        if (pct.compareTo(MIN_PCT) < 0) pct = MIN_PCT;
        if (pct.compareTo(MAX_PCT) > 0) pct = MAX_PCT;
        return pct.setScale(4, RoundingMode.HALF_UP);
    }
}
