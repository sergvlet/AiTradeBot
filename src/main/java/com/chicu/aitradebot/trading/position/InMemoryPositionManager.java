package com.chicu.aitradebot.trading.position;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryPositionManager implements PositionManager {

    private final Map<String, ActivePosition> positions = new ConcurrentHashMap<>();

    private String key(Long chatId, String symbol) {
        return chatId + ":" + symbol;
    }

    @Override
    public Optional<ActivePosition> get(Long chatId, String symbol) {
        return Optional.ofNullable(positions.get(key(chatId, symbol)));
    }

    @Override
    public boolean hasOpenPosition(Long chatId, String symbol) {
        return positions.containsKey(key(chatId, symbol));
    }

    @Override
    public void open(ActivePosition position) {
        positions.put(key(position.getChatId(), position.getSymbol()), position);
    }

    @Override
    public void close(Long chatId, String symbol) {
        positions.remove(key(chatId, symbol));
    }
}
