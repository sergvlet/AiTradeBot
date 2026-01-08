package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InMemoryPositionStore implements PositionStore {

    private final Set<String> inPos = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isInPosition(Long chatId, StrategyType type, String exchange, NetworkType network) {
        return inPos.contains(key(chatId, type, exchange, network));
    }

    @Override
    public void markOpened(Long chatId, StrategyType type, String exchange, NetworkType network) {
        inPos.add(key(chatId, type, exchange, network));
    }

    @Override
    public void markClosed(Long chatId, StrategyType type, String exchange, NetworkType network) {
        inPos.remove(key(chatId, type, exchange, network));
    }

    private static String key(Long chatId, StrategyType type, String exchange, NetworkType network) {
        return chatId + ":" + type + ":" + exchange + ":" + network;
    }
}
