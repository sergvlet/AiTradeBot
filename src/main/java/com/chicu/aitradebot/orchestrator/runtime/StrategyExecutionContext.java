package com.chicu.aitradebot.orchestrator.runtime;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ✅ ThreadLocal-контекст исполнения стратегии.
 * Нужен чтобы OrderService/TradeExecution/ML-гейт понимали:
 * кто сейчас исполняется, в каком режиме/фазе и по какому инструменту.
 */
public final class StrategyExecutionContext {

    private StrategyExecutionContext() {}

    public enum EventKind { TICK, CANDLE }

    public record Ctx(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            String symbol,
            String timeframe,

            AdvancedControlMode mode,
            String runPhase,

            EventKind eventKind,
            BigDecimal price,
            Instant eventTime
    ) {}

    private static final ThreadLocal<Ctx> TL = new ThreadLocal<>();

    public static Ctx current() {
        return TL.get();
    }

    public static Scope open(Ctx ctx) {
        TL.set(ctx);
        return Scope.INSTANCE;
    }

    public interface Scope extends AutoCloseable {
        Scope INSTANCE = TL::remove;
        @Override void close();
    }
}
