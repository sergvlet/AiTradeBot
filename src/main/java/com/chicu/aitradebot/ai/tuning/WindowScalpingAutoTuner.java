package com.chicu.aitradebot.ai.tuning;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.windowscalping.WindowScalpingStrategySettings;
import com.chicu.aitradebot.strategy.windowscalping.WindowScalpingStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WindowScalpingAutoTuner implements StrategyAutoTuner {

    private final WindowScalpingStrategySettingsService settingsService;

    @Override
    public StrategyType getStrategyType() {
        return StrategyType.WINDOW_SCALPING;
    }

    @Override
    public TuningResult tune(TuningRequest request) {

        if (request == null || request.chatId() == null) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("WINDOW_SCALPING tuner: request/chatId is null")
                    .build();
        }

        // защита от неверного роутинга
        if (request.strategyType() != null && request.strategyType() != StrategyType.WINDOW_SCALPING) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("WINDOW_SCALPING tuner: wrong strategyType=" + request.strategyType())
                    .build();
        }

        final Long chatId = request.chatId();

        WindowScalpingStrategySettings cur = settingsService.getOrCreate(chatId);
        if (cur == null) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("WINDOW_SCALPING tuner: settingsService.getOrCreate returned null")
                    .build();
        }

        // --- старые параметры (для UI/логов) ---
        Map<String, Object> oldParams = new LinkedHashMap<>();
        oldParams.put("windowSize", cur.getWindowSize());
        oldParams.put("entryFromLowPct", cur.getEntryFromLowPct());
        oldParams.put("entryFromHighPct", cur.getEntryFromHighPct());
        oldParams.put("minRangePct", cur.getMinRangePct());
        oldParams.put("maxSpreadPct", cur.getMaxSpreadPct());

        // --- безопасный "патч" (stub), чтобы проверить цепочку HYBRID/AI → apply → tune → save ---
        Integer windowSize = (cur.getWindowSize() != null) ? cur.getWindowSize() : 120;

        Double entryFromLowPct  = clampPct(orDefault(cur.getEntryFromLowPct(), 20.0));
        Double entryFromHighPct = clampPct(orDefault(cur.getEntryFromHighPct(), 20.0));

        // minRangePct/maxSpreadPct у тебя выглядят как проценты диапазона/спрэда (например 0.30 = 0.30%)
        Double minRangePct  = clampNonNegative(orDefault(cur.getMinRangePct(), 0.30));
        Double maxSpreadPct = clampNonNegative(orDefault(cur.getMaxSpreadPct(), 0.20));

        WindowScalpingStrategySettings patched = WindowScalpingStrategySettings.builder()
                .chatId(chatId)
                .windowSize(Math.max(1, windowSize))
                .entryFromLowPct(entryFromLowPct)
                .entryFromHighPct(entryFromHighPct)
                .minRangePct(minRangePct)
                .maxSpreadPct(maxSpreadPct)
                .build();

        settingsService.update(chatId, patched);

        // --- новые параметры (для UI/логов) ---
        Map<String, Object> newParams = new LinkedHashMap<>();
        newParams.put("windowSize", patched.getWindowSize());
        newParams.put("entryFromLowPct", patched.getEntryFromLowPct());
        newParams.put("entryFromHighPct", patched.getEntryFromHighPct());
        newParams.put("minRangePct", patched.getMinRangePct());
        newParams.put("maxSpreadPct", patched.getMaxSpreadPct());

        log.info("🧠 WINDOW_SCALPING tuned (chatId={}, reason={}, symbol={}, tf={})",
                chatId, safe(request.reason()), safe(request.symbol()), safe(request.timeframe()));

        return TuningResult.builder()
                .applied(true)
                .reason("WINDOW_SCALPING tuner applied (stub)")
                .modelVersion("stub-1")
                .scoreBefore(null)
                .scoreAfter(null)
                .oldParams(oldParams)
                .newParams(newParams)
                .build();
    }

    // =====================================================
    // helpers
    // =====================================================

    private static Double orDefault(Double v, double def) {
        return v != null ? v : def;
    }

    private static Double clampPct(Double v) {
        if (v == null) return null;
        if (v < 0) return 0.0;
        if (v > 100) return 100.0;
        return v;
    }

    private static Double clampNonNegative(Double v) {
        if (v == null) return null;
        return v < 0 ? 0.0 : v;
    }

    private static String safe(String s) {
        return s == null ? "—" : s;
    }
}
