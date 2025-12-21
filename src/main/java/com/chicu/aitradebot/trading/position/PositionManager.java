package com.chicu.aitradebot.trading.position;

import java.util.Optional;

public interface PositionManager {

    Optional<ActivePosition> get(Long chatId, String symbol);

    boolean hasOpenPosition(Long chatId, String symbol);

    void open(ActivePosition position);

    void close(Long chatId, String symbol);
}
