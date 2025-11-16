package com.chicu.aitradebot.strategy.core;

import java.util.Optional;

/** Опциональная «надстройка» — если стратегия её реализует, дашборд возьмёт символ/TP/SL/свечи прямо из неё. */
public interface DashboardSupport {
    String getSymbol(long chatId);
    Optional<Double> getTakeProfitPct(long chatId);
    Optional<Double> getStopLossPct(long chatId);
    Optional<CandleProvider> getCandleProvider();
}
