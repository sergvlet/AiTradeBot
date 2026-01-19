package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.ai.tuning.eval.BacktestCandlePort;
import com.chicu.aitradebot.ai.tuning.eval.BacktestMetrics;
import com.chicu.aitradebot.ai.tuning.eval.BacktestPort;
import com.chicu.aitradebot.ai.tuning.eval.CandleBar;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class JpaBacktestPort implements BacktestPort {

    private final StrategySettingsService strategySettingsService;
    private final BacktestCandlePort candlePort;

    @Override
    public BacktestMetrics backtest(Long chatId,
                                    StrategyType type,
                                    String symbolOverride,
                                    String timeframeOverride,
                                    Map<String, Object> candidateParams,
                                    Instant startAt,
                                    Instant endAt) {

        try {
            StrategySettings settings = strategySettingsService.findAllByChatId(chatId, null, null)
                    .stream()
                    .filter(s -> s.getType() == type)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("StrategySettings not found: chatId=" + chatId + ", type=" + type));

            String symbol = (symbolOverride != null && !symbolOverride.isBlank())
                    ? symbolOverride
                    : settings.getSymbol();

            String timeframe = (timeframeOverride != null && !timeframeOverride.isBlank())
                    ? timeframeOverride
                    : settings.getTimeframe();

            if (symbol == null || symbol.isBlank()) return BacktestMetrics.fail("symbol is null/blank");
            if (timeframe == null || timeframe.isBlank()) return BacktestMetrics.fail("timeframe is null/blank");
            if (startAt == null || endAt == null) return BacktestMetrics.fail("startAt/endAt is null");
            if (!endAt.isAfter(startAt)) return BacktestMetrics.fail("endAt must be after startAt");

            int limit = resolveLimit(settings, candidateParams);

            List<CandleBar> candles = candlePort.load(chatId, type, symbol, timeframe, startAt, endAt, limit);
            if (candles == null || candles.isEmpty()) {
                return BacktestMetrics.fail("No candles loaded");
            }



            return BacktestMetrics.fail("Unsupported strategy for backtest: " + type);

        } catch (Exception e) {
            log.warn("Backtest failed: {}", e.getMessage());
            return BacktestMetrics.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static int resolveLimit(StrategySettings settings, Map<String, Object> candidateParams) {
        // 1) тюнер может передать candlesLimit
        if (candidateParams != null) {
            Object v = candidateParams.get("candlesLimit");
            Integer parsed = tryInt(v);
            if (parsed != null && parsed > 0) return parsed;
        }
        // 2) если у тебя есть cachedCandlesLimit в StrategySettings — используем
        Integer cached = settings.getCachedCandlesLimit();
        if (cached != null && cached > 0) return cached;

        // 3) “как на прод”: большинство REST klines имеет лимит 1000
        return 1000;
    }

    private static Integer tryInt(Object v) {
        if (v == null) return null;
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return (int) Math.min(Integer.MAX_VALUE, l);
        if (v instanceof Double d) return (int) Math.round(d);
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        }
        return null;
    }
}
