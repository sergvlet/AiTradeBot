package com.chicu.aitradebot.strategy.ema;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.core.TradingStrategy;
import com.chicu.aitradebot.strategy.core.signal.Signal;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import com.chicu.aitradebot.strategy.registry.StrategyBinding;
import com.chicu.aitradebot.trade.TradeExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@StrategyBinding(StrategyType.EMA_CROSSOVER)
@Slf4j
@Component
@RequiredArgsConstructor
public class EmaCrossoverStrategyV4 implements TradingStrategy {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    private final StrategyLivePublisher live;
    private final StrategySettingsService strategySettingsService;
    private final EmaCrossoverStrategySettingsService emaSettingsService;
    private final TradeExecutionService tradeExecutionService;

    private final Map<Long, LocalState> states = new ConcurrentHashMap<>();

    private static class LocalState {
        boolean active;
        Instant startedAt;

        StrategySettings strategySettings;
        EmaCrossoverStrategySettings cfg;

        String symbol;
        String exchange;
        NetworkType network;

        boolean inPosition;
    }

    // =====================================================
    // START / STOP
    // =====================================================

    @Override
    public void start(Long chatId, String ignored) {

        StrategySettings ss = loadStrategySettings(chatId);
        EmaCrossoverStrategySettings cfg = emaSettingsService.getOrCreate(chatId);

        LocalState st = new LocalState();
        st.active = true;
        st.startedAt = Instant.now();
        st.strategySettings = ss;
        st.cfg = cfg;

        st.symbol = ss.getSymbol();
        st.exchange = ss.getExchangeName();
        st.network = ss.getNetworkType();

        states.put(chatId, st);

        log.info("[EMA] ▶ START chatId={} symbol={} emaFast={} emaSlow={}",
                chatId,
                st.symbol,
                cfg.getEmaFast(),
                cfg.getEmaSlow()
        );

        safeLive(() -> live.pushState(chatId, StrategyType.EMA_CROSSOVER, st.symbol, true));
        safeLive(() -> live.pushSignal(chatId, StrategyType.EMA_CROSSOVER, st.symbol, null,
                Signal.hold("started")));
    }

    @Override
    public void stop(Long chatId, String ignored) {

        LocalState st = states.remove(chatId);
        if (st == null) return;

        safeLive(() -> live.pushState(chatId, StrategyType.EMA_CROSSOVER, st.symbol, false));

        log.info("[EMA] ⏹ STOP chatId={} symbol={}", chatId, st.symbol);
    }

    @Override
    public boolean isActive(Long chatId) {
        LocalState st = states.get(chatId);
        return st != null && st.active;
    }

    @Override
    public Instant getStartedAt(Long chatId) {
        LocalState st = states.get(chatId);
        return st != null ? st.startedAt : null;
    }

    // =====================================================
    // PRICE UPDATE (каркас)
    // =====================================================

    @Override
    public void onPriceUpdate(Long chatId, String symbolFromTick, BigDecimal price, Instant ts) {

        LocalState st = states.get(chatId);
        if (st == null || !st.active) return;

        // 🔹 EMA расчёт будет добавлен позже через CandleService

        safeLive(() -> live.pushSignal(
                chatId,
                StrategyType.EMA_CROSSOVER,
                st.symbol,
                null,
                Signal.hold("waiting_for_ema_crossover")
        ));
    }

    // =====================================================
    // LOAD StrategySettings
    // =====================================================

    private StrategySettings loadStrategySettings(Long chatId) {
        return strategySettingsService
                .findAllByChatId(chatId, null, null)
                .stream()
                .filter(s -> s.getType() == StrategyType.EMA_CROSSOVER)
                .sorted(
                        Comparator
                                .comparing(StrategySettings::getUpdatedAt,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .reversed()
                )
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("StrategySettings для EMA_CROSSOVER не найдены")
                );
    }

    // =====================================================
    // UTILS
    // =====================================================

    private void safeLive(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt == null ? null : ldt.atZone(ZONE).toInstant();
    }
}
