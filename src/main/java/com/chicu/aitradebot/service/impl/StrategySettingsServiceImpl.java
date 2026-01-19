package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategySettingsServiceImpl implements StrategySettingsService {

    private final StrategySettingsRepository repo;

    @Override
    @Transactional
    public StrategySettings save(StrategySettings s) {
        if (s == null) throw new IllegalArgumentException("StrategySettings is null");

        // ✅ не затираем режим, если он уже выбран
        if (s.getAdvancedControlMode() == null) {
            s.setAdvancedControlMode(AdvancedControlMode.MANUAL);
        }

        // ✅ корректное время (Instant -> LocalDateTime напрямую нельзя)
        s.setUpdatedAt(LocalDateTime.now());

        return repo.save(s);
    }

    @Override
    @Transactional(readOnly = true)
    public StrategySettings getSettings(long chatId, StrategyType type, String exchange, NetworkType network) {
        if (chatId <= 0 || type == null || network == null) return null;
        String ex = normalizeExchange(exchange);
        return repo.findByChatIdAndTypeAndExchangeNameAndNetworkType(chatId, type, ex, network).orElse(null);
    }

    @Override
    @Transactional
    public StrategySettings getOrCreate(long chatId, StrategyType type, String exchange, NetworkType network) {
        if (chatId <= 0) throw new IllegalArgumentException("chatId must be positive");
        if (type == null) throw new IllegalArgumentException("type must be provided");
        if (network == null) throw new IllegalArgumentException("network must be provided");

        String ex = normalizeExchange(exchange);

        return repo.findByChatIdAndTypeAndExchangeNameAndNetworkType(chatId, type, ex, network)
                .orElseGet(() -> createOne(chatId, type, ex, network));
    }

    private StrategySettings createOne(long chatId, StrategyType type, String exchange, NetworkType network) {
        LocalDateTime now = LocalDateTime.now();

        StrategySettings s = StrategySettings.builder()
                .chatId(chatId)
                .type(type)
                .exchangeName(exchange)
                .networkType(network)
                .active(false)
                .advancedControlMode(AdvancedControlMode.MANUAL)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            StrategySettings saved = repo.save(s);
            log.info("🆕 Created StrategySettings chatId={} type={} ex={} net={} id={}",
                    chatId, type, exchange, network, saved.getId());
            return saved;
        } catch (DataIntegrityViolationException dup) {
            // ✅ если два потока одновременно создали — просто читаем существующую (UNIQUE спасает)
            return repo.findByChatIdAndTypeAndExchangeNameAndNetworkType(chatId, type, exchange, network)
                    .orElseThrow(() -> dup);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<StrategySettings> findAllByChatId(long chatId, String exchange, NetworkType network) {
        String ex = normalizeExchange(exchange);
        if (network == null) {
            return repo.findAllByChatIdAndExchangeName(chatId, ex);
        }
        return repo.findAllByChatIdAndExchangeNameAndNetworkType(chatId, ex, network);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StrategySettings> findAllByChatId(long chatId, String exchange) {
        String ex = normalizeExchange(exchange);
        return repo.findAllByChatIdAndExchangeName(chatId, ex);
    }

    @Override
    @Transactional
    public void updateRiskFromUi(long chatId, StrategyType type, String exchange, NetworkType network,
                                 BigDecimal dailyLossLimitPct, BigDecimal riskPerTradePct) {
        StrategySettings s = getOrCreate(chatId, type, exchange, network);
        s.setDailyLossLimitPct(dailyLossLimitPct);
        s.setRiskPerTradePct(riskPerTradePct);
        save(s);
    }

    @Override
    @Transactional
    public void updateRiskFromAi(long chatId, StrategyType type, String exchange, NetworkType network,
                                 BigDecimal newRiskPerTradePct) {
        StrategySettings s = getOrCreate(chatId, type, exchange, network);
        s.setRiskPerTradePct(newRiskPerTradePct);
        save(s);
    }

    private static String normalizeExchange(String exchange) {
        if (exchange == null) return "BINANCE";
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        return ex.isEmpty() ? "BINANCE" : ex;
    }
}
