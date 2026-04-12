package com.chicu.aitradebot.ai.runtime;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
public class StrategyPerformanceMonitor {

    private static final int TRADE_WINDOW = 20;
    private static final int FEATURE_WINDOW = 24;
    private static final int BLOCKER_WINDOW = 40;

    private record Key(long chatId, StrategyType type, String exchange, NetworkType network) {}

    private static final class State {
        long chatId;
        StrategyType type;
        String exchange;
        NetworkType network;
        String symbol;
        String timeframe;
        Instant startedAt;
        Instant lastEntryAt;
        Instant lastExitAt;
        long ticks;
        long candles;
        long entries;
        long exits;
        long closedTrades;
        long wins;
        long losses;
        int winStreak;
        int lossStreak;
        long candlesWithoutEntry;
        long candlesSinceLastEntry;
        long candlesSinceLastExit;
        long candlesInPosition;
        boolean inPosition;
        String lastBlockReason;

        final Deque<BigDecimal> pnlPctWindow = new ArrayDeque<>();
        final Deque<BigDecimal> pnlUsdWindow = new ArrayDeque<>();
        final Deque<BigDecimal> holdSecWindow = new ArrayDeque<>();
        final Deque<Boolean> winWindow = new ArrayDeque<>();
        final Deque<BigDecimal> atrWindow = new ArrayDeque<>();
        final Deque<BigDecimal> spreadWindow = new ArrayDeque<>();
        final Deque<BigDecimal> volumeRatioWindow = new ArrayDeque<>();
        final Deque<String> blockerWindow = new ArrayDeque<>();

        void reset(long chatId,
                   StrategyType type,
                   String exchange,
                   NetworkType network,
                   String symbol,
                   String timeframe,
                   Instant now) {
            this.chatId = chatId;
            this.type = type;
            this.exchange = exchange;
            this.network = network;
            this.symbol = symbol;
            this.timeframe = timeframe;
            this.startedAt = now;
            this.lastEntryAt = null;
            this.lastExitAt = null;
            this.ticks = 0L;
            this.candles = 0L;
            this.entries = 0L;
            this.exits = 0L;
            this.closedTrades = 0L;
            this.wins = 0L;
            this.losses = 0L;
            this.winStreak = 0;
            this.lossStreak = 0;
            this.candlesWithoutEntry = 0L;
            this.candlesSinceLastEntry = 0L;
            this.candlesSinceLastExit = 0L;
            this.candlesInPosition = 0L;
            this.inPosition = false;
            this.lastBlockReason = null;
            this.pnlPctWindow.clear();
            this.pnlUsdWindow.clear();
            this.holdSecWindow.clear();
            this.winWindow.clear();
            this.atrWindow.clear();
            this.spreadWindow.clear();
            this.volumeRatioWindow.clear();
            this.blockerWindow.clear();
        }
    }

    private final ConcurrentMap<Key, State> states = new ConcurrentHashMap<>();

    public void onStrategyStarted(long chatId,
                                  StrategyType type,
                                  String exchange,
                                  NetworkType network,
                                  String symbol,
                                  String timeframe) {
        Key key = new Key(chatId, type, safeUpper(exchange), network);
        State state = states.computeIfAbsent(key, ignored -> new State());
        synchronized (state) {
            state.reset(chatId, type, safeUpper(exchange), network, safeUpper(symbol), safeLower(timeframe), Instant.now());
        }
        log.info("📊 [PERF] monitor started chatId={} type={} ex={} net={} sym={} tf={}",
                chatId, type, safeUpper(exchange), network, safeUpper(symbol), safeLower(timeframe));
    }

    public void onStrategyStopped(long chatId, StrategyType type, String exchange, NetworkType network) {
        states.remove(new Key(chatId, type, safeUpper(exchange), network));
    }

    public void onTick(long chatId, StrategyType type, String exchange, NetworkType network) {
        State state = states.get(new Key(chatId, type, safeUpper(exchange), network));
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.ticks++;
        }
    }

    public void onCandleObserved(long chatId,
                                 StrategyType type,
                                 String exchange,
                                 NetworkType network,
                                 String symbol,
                                 String timeframe,
                                 BigDecimal atrPct,
                                 BigDecimal spreadPct,
                                 BigDecimal volumeRatio,
                                 Instant at) {
        State state = states.get(new Key(chatId, type, safeUpper(exchange), network));
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (safeUpper(symbol) != null) {
                state.symbol = safeUpper(symbol);
            }
            if (safeLower(timeframe) != null) {
                state.timeframe = safeLower(timeframe);
            }
            state.candles++;
            if (state.inPosition) {
                state.candlesInPosition++;
                state.candlesWithoutEntry = 0L;
                state.candlesSinceLastExit = 0L;
            } else {
                state.candlesWithoutEntry++;
                state.candlesInPosition = 0L;
                if (state.lastEntryAt != null) {
                    state.candlesSinceLastEntry++;
                } else {
                    state.candlesSinceLastEntry = state.candlesWithoutEntry;
                }
            }
            if (state.lastExitAt != null) {
                state.candlesSinceLastExit++;
            }
            pushWindow(state.atrWindow, nz(atrPct), FEATURE_WINDOW);
            pushWindow(state.spreadWindow, nz(spreadPct), FEATURE_WINDOW);
            pushWindow(state.volumeRatioWindow, nz(volumeRatio), FEATURE_WINDOW);
        }
    }

    public void onHold(long chatId,
                       StrategyType type,
                       String exchange,
                       NetworkType network,
                       String blockerReason,
                       Instant at) {
        State state = states.get(new Key(chatId, type, safeUpper(exchange), network));
        if (state == null || blockerReason == null || blockerReason.isBlank()) {
            return;
        }
        synchronized (state) {
            state.lastBlockReason = blockerReason.trim();
            pushBlocker(state.blockerWindow, state.lastBlockReason, BLOCKER_WINDOW);
        }
    }

    public void onEntry(long chatId,
                        StrategyType type,
                        String exchange,
                        NetworkType network,
                        String symbol,
                        String timeframe,
                        Instant at) {
        State state = states.get(new Key(chatId, type, safeUpper(exchange), network));
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.entries++;
            state.inPosition = true;
            state.lastEntryAt = at;
            state.symbol = safeUpper(symbol);
            state.timeframe = safeLower(timeframe);
            state.candlesWithoutEntry = 0L;
            state.candlesSinceLastEntry = 0L;
            state.candlesInPosition = 0L;
            state.lastBlockReason = null;
        }
    }

    public void onExit(long chatId,
                       StrategyType type,
                       String exchange,
                       NetworkType network,
                       String symbol,
                       String timeframe,
                       BigDecimal pnlPct,
                       BigDecimal pnlUsd,
                       String exitReason,
                       BigDecimal holdSeconds,
                       Instant at) {
        State state = states.get(new Key(chatId, type, safeUpper(exchange), network));
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.exits++;
            state.closedTrades++;
            state.inPosition = false;
            state.lastExitAt = at;
            state.symbol = safeUpper(symbol);
            state.timeframe = safeLower(timeframe);
            state.candlesSinceLastExit = 0L;
            state.candlesInPosition = 0L;
            state.lastBlockReason = exitReason;

            BigDecimal safePct = nz(pnlPct);
            BigDecimal safeUsd = nz(pnlUsd);
            pushWindow(state.pnlPctWindow, safePct, TRADE_WINDOW);
            pushWindow(state.pnlUsdWindow, safeUsd, TRADE_WINDOW);
            pushWindow(state.holdSecWindow, nz(holdSeconds), TRADE_WINDOW);

            boolean win = safePct.signum() > 0;
            state.winWindow.addLast(win);
            while (state.winWindow.size() > TRADE_WINDOW) {
                state.winWindow.removeFirst();
            }

            if (win) {
                state.wins++;
                state.winStreak++;
                state.lossStreak = 0;
            } else if (safePct.signum() < 0) {
                state.losses++;
                state.lossStreak++;
                state.winStreak = 0;
            } else {
                state.winStreak = 0;
                state.lossStreak = 0;
            }
        }
    }

    public StrategyPerformanceSnapshot getSnapshot(long chatId,
                                                   StrategyType type,
                                                   String exchange,
                                                   NetworkType network) {
        State state = states.get(new Key(chatId, type, safeUpper(exchange), network));
        if (state == null) {
            return new StrategyPerformanceSnapshot(
                    chatId,
                    type,
                    safeUpper(exchange),
                    network,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0,
                    0,
                    0L,
                    0L,
                    0L,
                    0L,
                    false,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    null,
                    Map.of()
            );
        }

        synchronized (state) {
            Map<String, Integer> histogram = blockerHistogram(state.blockerWindow);
            String dominant = histogram.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(state.lastBlockReason);

            return new StrategyPerformanceSnapshot(
                    state.chatId,
                    state.type,
                    state.exchange,
                    state.network,
                    state.symbol,
                    state.timeframe,
                    state.startedAt,
                    state.lastEntryAt,
                    state.lastExitAt,
                    state.ticks,
                    state.candles,
                    state.entries,
                    state.exits,
                    state.closedTrades,
                    state.wins,
                    state.losses,
                    state.winStreak,
                    state.lossStreak,
                    state.candlesWithoutEntry,
                    state.candlesSinceLastEntry,
                    state.candlesSinceLastExit,
                    state.candlesInPosition,
                    state.inPosition,
                    avg(state.pnlPctWindow),
                    avg(state.pnlUsdWindow),
                    avg(state.pnlPctWindow),
                    winRate(state.winWindow),
                    avg(state.holdSecWindow),
                    avg(state.atrWindow),
                    avg(state.spreadWindow),
                    avg(state.volumeRatioWindow),
                    state.lastBlockReason,
                    dominant,
                    histogram
            );
        }
    }

    private static void pushBlocker(Deque<String> window, String value, int max) {
        if (value == null || value.isBlank()) {
            return;
        }
        window.addLast(value.trim());
        while (window.size() > max) {
            window.removeFirst();
        }
    }

    private static void pushWindow(Deque<BigDecimal> window, BigDecimal value, int max) {
        window.addLast(nz(value));
        while (window.size() > max) {
            window.removeFirst();
        }
    }

    private static Map<String, Integer> blockerHistogram(Deque<String> window) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String blocker : window) {
            out.merge(blocker, 1, Integer::sum);
        }
        return out;
    }

    private static BigDecimal avg(Deque<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            sum = sum.add(nz(value));
        }
        return sum.divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal winRate(Deque<Boolean> values) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        long wins = values.stream().filter(Boolean::booleanValue).count();
        return BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP) : value.setScale(8, RoundingMode.HALF_UP);
    }

    private static String safeUpper(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase();
    }

    private static String safeLower(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase();
    }
}
