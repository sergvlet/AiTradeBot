package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategySettingsServiceImpl implements StrategySettingsService {

    private final StrategySettingsRepository repo;

    private final Map<String, StrategySettings> cache = new ConcurrentHashMap<>();

    private String key(long chatId, StrategyType type, String exchange, NetworkType network) {
        return chatId + ":" + type.name() + ":" + exchange + ":" + network.name();
    }

    // =========================
    // SAVE
    // =========================
    @Override
    public StrategySettings save(StrategySettings s) {

        if (s.getAdvancedControlMode() == null) {
            s.setAdvancedControlMode(AdvancedControlMode.MANUAL);
        }

        StrategySettings saved = repo.save(s);

        cache.put(
                key(
                        saved.getChatId(),
                        saved.getType(),
                        saved.getExchangeName(),
                        saved.getNetworkType()
                ),
                saved
        );

        return saved;
    }

    // =========================
    // FIND ALL (UI)
    // =========================
    @Override
    public List<StrategySettings> findAllByChatId(
            long chatId,
            String exchange,
            NetworkType network
    ) {
        return repo.findAll().stream()
                .filter(s ->
                        s.getChatId() == chatId &&
                        exchange.equals(s.getExchangeName()) &&
                        network == s.getNetworkType()
                )
                .toList();
    }

    // =========================
    // GET
    // =========================
    @Override
    public StrategySettings getSettings(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {

        String k = key(chatId, type, exchange, network);

        StrategySettings cached = cache.get(k);
        if (cached != null) {
            return cached;
        }

        return repo
                .findTopByChatIdAndTypeAndExchangeNameAndNetworkTypeOrderByIdDesc(
                        chatId, type, exchange, network
                )
                .map(s -> {
                    cache.put(k, s);
                    return s;
                })
                .orElse(null);
    }

    // =========================
    // GET OR CREATE
    // =========================
    @Override
    public StrategySettings getOrCreate(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {

        StrategySettings existing =
                getSettings(chatId, type, exchange, network);

        if (existing != null) {
            return existing;
        }

        log.warn(
                "🆕 Создаём StrategySettings chatId={}, type={}, exchange={}, network={}",
                chatId, type, exchange, network
        );

        StrategySettings s = StrategySettings.builder()
                .chatId(chatId)
                .type(type)
                .symbol("BTCUSDT")
                .timeframe("1m")
                .cachedCandlesLimit(500)
                .capitalUsd(BigDecimal.valueOf(100))
                .commissionPct(BigDecimal.valueOf(0.05))
                .riskPerTradePct(BigDecimal.valueOf(1))
                .dailyLossLimitPct(BigDecimal.valueOf(20))
                .takeProfitPct(BigDecimal.valueOf(1))
                .stopLossPct(BigDecimal.valueOf(1))
                .reinvestProfit(false)
                .exchangeName(exchange)
                .networkType(network)
                .advancedControlMode(AdvancedControlMode.MANUAL)
                .active(false)
                .build();

        return save(s);
    }
    @Override
    public Optional<StrategySettings> findLatest(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {
        return repo
                .findTopByChatIdAndTypeAndExchangeNameAndNetworkTypeOrderByIdDesc(
                        chatId, type, exchange, network
                );
    }


}
