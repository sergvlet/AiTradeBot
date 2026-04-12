package com.chicu.aitradebot.strategy.fibonacci_grid;

import com.chicu.aitradebot.ai.tuning.StrategyAutoTuner;
import com.chicu.aitradebot.ai.tuning.TuningRequest;
import com.chicu.aitradebot.ai.tuning.TuningResult;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class FibonacciGridAutoTuner implements StrategyAutoTuner {

    private static final BigDecimal MIN_DISTANCE = new BigDecimal("0.15");
    private static final BigDecimal MAX_DISTANCE = new BigDecimal("5.00");
    private static final BigDecimal MIN_TP = new BigDecimal("0.20");
    private static final BigDecimal MAX_TP = new BigDecimal("5.00");
    private static final BigDecimal MIN_SL = new BigDecimal("0.20");
    private static final BigDecimal MAX_SL = new BigDecimal("8.00");

    private final FibonacciGridStrategySettingsService settingsService;

    @Override
    public StrategyType getStrategyType() {
        return StrategyType.FIBONACCI_GRID;
    }

    @Override
    public TuningResult tune(TuningRequest request) {
        if (request == null || request.chatId() == null || request.chatId() <= 0) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("chatId не задан")
                    .build();
        }

        FibonacciGridStrategySettings cur = settingsService.getOrCreate(request.chatId());
        if (cur == null) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("fibo settings not found")
                    .build();
        }

        FibonacciGridStrategySettings next = copy(cur);
        String reason = normalizeReason(request.reason());

        if (isNoTradesReason(reason)) {
            // Нужно чаще ловить входы: уменьшаем шаг и расширяем сетку,
            // но не снижаем количество уровней, если оно уже больше текущего потолка поиска.
            next.setDistancePct(clamp(cur.getDistancePct().multiply(new BigDecimal("0.85")), MIN_DISTANCE, MAX_DISTANCE));
            int nextLevels = Math.min(14, Math.max(2, cur.getGridLevels() + 1));
            if (nextLevels < cur.getGridLevels()) {
                nextLevels = cur.getGridLevels();
            }
            next.setGridLevels(nextLevels);
            next.setTakeProfitPct(clamp(cur.getTakeProfitPct().multiply(new BigDecimal("0.95")), MIN_TP, MAX_TP));
        } else if (isLossReason(reason)) {
            // Нужно меньше агрессии: шаг чуть шире, уровней чуть меньше, SL чуть уже.
            next.setDistancePct(clamp(cur.getDistancePct().multiply(new BigDecimal("1.15")), MIN_DISTANCE, MAX_DISTANCE));
            next.setGridLevels(Math.max(2, cur.getGridLevels() - 1));
            next.setStopLossPct(clamp(cur.getStopLossPct().multiply(new BigDecimal("0.90")), MIN_SL, MAX_SL));
            next.setTakeProfitPct(clamp(cur.getTakeProfitPct().multiply(new BigDecimal("0.92")), MIN_TP, MAX_TP));
        } else {
            // Мягкий periodic/startup-тюнинг.
            next.setDistancePct(clamp(cur.getDistancePct().multiply(new BigDecimal("0.97")), MIN_DISTANCE, MAX_DISTANCE));
            next.setTakeProfitPct(clamp(cur.getTakeProfitPct(), MIN_TP, MAX_TP));
            next.setStopLossPct(clamp(cur.getStopLossPct(), MIN_SL, MAX_SL));
        }

        boolean changed =
                !Objects.equals(cur.getGridLevels(), next.getGridLevels()) ||
                compare(cur.getDistancePct(), next.getDistancePct()) != 0 ||
                compare(cur.getTakeProfitPct(), next.getTakeProfitPct()) != 0 ||
                compare(cur.getStopLossPct(), next.getStopLossPct()) != 0;

        if (!changed) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("no_improvement")
                    .modelVersion("fibo-tuner-v1")
                    .oldParams(snapshot(cur))
                    .newParams(snapshot(next))
                    .build();
        }

        FibonacciGridStrategySettings saved = settingsService.update(request.chatId(), next);

        log.info("🧠 FIBO TUNER applied chatId={} reason={} levels {}->{} distance {}->{} tp {}->{} sl {}->{}",
                request.chatId(),
                reason,
                cur.getGridLevels(),
                saved.getGridLevels(),
                strip(cur.getDistancePct()),
                strip(saved.getDistancePct()),
                strip(cur.getTakeProfitPct()),
                strip(saved.getTakeProfitPct()),
                strip(cur.getStopLossPct()),
                strip(saved.getStopLossPct()));

        return TuningResult.builder()
                .applied(true)
                .reason(reason)
                .modelVersion("fibo-tuner-v1")
                .oldParams(snapshot(cur))
                .newParams(snapshot(saved))
                .build();
    }

    /**
     * AutoTunerOrchestrator вызывает этот метод через reflection после NO_TRADES.
     */
    public void adjustCoarseFilters(TuningRequest request) {
        if (request == null || request.chatId() == null || request.chatId() <= 0) {
            return;
        }

        FibonacciGridStrategySettings cur = settingsService.getOrCreate(request.chatId());
        FibonacciGridStrategySettings next = copy(cur);
        next.setDistancePct(clamp(cur.getDistancePct().multiply(new BigDecimal("0.80")), MIN_DISTANCE, MAX_DISTANCE));
        next.setGridLevels(Math.min(14, Math.max(cur.getGridLevels(), cur.getGridLevels() + 2)));
        next.setTakeProfitPct(clamp(cur.getTakeProfitPct().multiply(new BigDecimal("0.90")), MIN_TP, MAX_TP));

        boolean changed =
                !Objects.equals(cur.getGridLevels(), next.getGridLevels()) ||
                compare(cur.getDistancePct(), next.getDistancePct()) != 0 ||
                compare(cur.getTakeProfitPct(), next.getTakeProfitPct()) != 0;

        if (!changed) {
            return;
        }

        settingsService.update(request.chatId(), next);

        log.warn("🧠 FIBO TUNER coarse-adjust chatId={} levels {}->{} distance {}->{} tp {}->{}",
                request.chatId(),
                cur.getGridLevels(),
                next.getGridLevels(),
                strip(cur.getDistancePct()),
                strip(next.getDistancePct()),
                strip(cur.getTakeProfitPct()),
                strip(next.getTakeProfitPct()));
    }

    public void onNoTrades(TuningRequest request) {
        adjustCoarseFilters(request);
    }

    private FibonacciGridStrategySettings copy(FibonacciGridStrategySettings src) {
        return FibonacciGridStrategySettings.builder()
                .chatId(src.getChatId())
                .gridLevels(src.getGridLevels())
                .distancePct(src.getDistancePct())
                .takeProfitPct(src.getTakeProfitPct())
                .stopLossPct(src.getStopLossPct())
                .orderVolume(src.getOrderVolume())
                .build();
    }

    private Map<String, Object> snapshot(FibonacciGridStrategySettings s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gridLevels", s.getGridLevels());
        out.put("distancePct", s.getDistancePct());
        out.put("takeProfitPct", s.getTakeProfitPct());
        out.put("stopLossPct", s.getStopLossPct());
        out.put("orderVolume", s.getOrderVolume());
        return out;
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "fibo_tune";
        }
        return reason.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isNoTradesReason(String reason) {
        return reason.contains("no_trade") || reason.contains("no_trades") || reason.contains("starvation") || reason.contains("hold:");
    }

    private boolean isLossReason(String reason) {
        return reason.contains("sl") || reason.contains("loss") || reason.contains("after-close:loss") || reason.contains("after-close:sl");
    }

    private BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value == null) return min;
        BigDecimal scaled = value.setScale(6, RoundingMode.HALF_UP);
        if (scaled.compareTo(min) < 0) return min;
        if (scaled.compareTo(max) > 0) return max;
        return scaled.stripTrailingZeros();
    }

    private int compare(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    private String strip(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }
}

