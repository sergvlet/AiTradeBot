package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.service.StrategySettingsService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategySettingsServiceImpl implements StrategySettingsService {

    private final StrategySettingsRepository repo;

    /**
     * Кэш используется ТОЛЬКО при полном контексте:
     * chatId + type + exchange + network
     */
    private final Map<String, StrategySettings> cache = new ConcurrentHashMap<>();

    // =====================================================================
    // CACHE KEY
    // =====================================================================
    private String key(long chatId, StrategyType type, String exchange, NetworkType network) {
        String ex = normalizeExchange(exchange);
        String net = (network != null ? network.name() : "NULL");
        return chatId + ":" + type.name() + ":" + ex + ":" + net;
    }

    // =====================================================================
    // SAVE (очищает cache для chatId+type)
    // =====================================================================
    @Override
    public StrategySettings save(StrategySettings s) {

        // defaults
        if (s.getAdvancedControlMode() == null) {
            s.setAdvancedControlMode(AdvancedControlMode.MANUAL);
        }

        // контекст должен быть нормализован и не null (у тебя теперь NOT NULL в entity)
        s.setExchangeName(normalizeExchange(s.getExchangeName()));
        if (s.getNetworkType() == null) {
            s.setNetworkType(NetworkType.TESTNET);
        }

        // минимальные дефолты, чтобы не словить NOT NULL
        if (s.getSymbol() == null || s.getSymbol().isBlank()) {
            s.setSymbol("BTCUSDT");
        }
        if (s.getTimeframe() == null || s.getTimeframe().isBlank()) {
            s.setTimeframe("1m");
        }
        if (s.getCachedCandlesLimit() == null || s.getCachedCandlesLimit() < 50) {
            s.setCachedCandlesLimit(500);
        }

        StrategySettings saved = repo.save(s);

        // ❗ сбрасываем все старые ключи этого chatId+type (потому что данные могли поменяться)
        cache.entrySet().removeIf(e ->
                e.getKey().startsWith(saved.getChatId() + ":" + saved.getType().name() + ":")
        );

        // кладём актуальный
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

    // =====================================================================
    // FIND ALL (UI)
    // =====================================================================
    @Override
    public List<StrategySettings> findAllByChatId(
            long chatId,
            String exchange,
            NetworkType network
    ) {
        String ex = exchange == null ? null : normalizeExchange(exchange);

        return repo.findByChatId(chatId).stream()
                .filter(s -> ex == null || ex.equalsIgnoreCase(s.getExchangeName()))
                .filter(s -> network == null || network == s.getNetworkType())
                .toList();
    }

    @Override
    public List<StrategySettings> findAllByChatId(long chatId, String exchange) {
        String ex = exchange == null ? null : normalizeExchange(exchange);

        return repo.findByChatId(chatId).stream()
                .filter(s -> ex == null || ex.equalsIgnoreCase(s.getExchangeName()))
                .toList();
    }

    // =====================================================================
    // GET (legacy)
    // =====================================================================
    @Override
    @Deprecated
    public StrategySettings getSettings(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {

        // если нет полного контекста — fallback (но лучше UI всегда давать exchange+network)
        if (exchange == null || exchange.isBlank() || network == null) {
            return repo
                    .findTopByChatIdAndTypeOrderByUpdatedAtDescIdDesc(chatId, type)
                    .orElse(null);
        }

        String ex = normalizeExchange(exchange);
        String k = key(chatId, type, ex, network);

        StrategySettings cached = cache.get(k);
        if (cached != null) {
            return cached;
        }

        return repo
                .findTopByChatIdAndTypeAndExchangeNameAndNetworkTypeOrderByUpdatedAtDescIdDesc(
                        chatId, type, ex, network
                )
                .map(s -> {
                    cache.put(k, s);
                    return s;
                })
                .orElse(null);
    }

    // =====================================================================
    // GET OR CREATE (СТРОГО по контексту)
    // =====================================================================
    @Override
    public StrategySettings getOrCreate(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {
        String ex = normalizeExchange(exchange);
        NetworkType net = (network != null ? network : NetworkType.TESTNET);

        // 1) cache
        String k = key(chatId, type, ex, net);
        StrategySettings cached = cache.get(k);
        if (cached != null) return cached;

        // 2) exact from DB
        Optional<StrategySettings> exactOpt =
                repo.findTopByChatIdAndTypeAndExchangeNameAndNetworkTypeOrderByUpdatedAtDescIdDesc(
                        chatId, type, ex, net
                );

        if (exactOpt.isPresent()) {
            StrategySettings exact = exactOpt.get();
            cache.put(k, exact);
            return exact;
        }

        // 3) create NEW record for this exact context
        StrategySettings created = StrategySettings.builder()
                .chatId(chatId)
                .type(type)
                .exchangeName(ex)
                .networkType(net)

                // instrument defaults
                .symbol("BTCUSDT")
                .timeframe("1m")
                .cachedCandlesLimit(500)

                // general defaults
                .accountAsset("USDT")
                .maxExposureUsd(BigDecimal.valueOf(100).setScale(6, RoundingMode.HALF_UP))
                .maxExposurePct(null)
                .dailyLossLimitPct(BigDecimal.valueOf(20).setScale(4, RoundingMode.HALF_UP))
                .reinvestProfit(false)

                // risk defaults
                .riskPerTradePct(BigDecimal.valueOf(1).setScale(4, RoundingMode.HALF_UP))
                .minRiskReward(null)
                .leverage(1)

                // trade defaults
                .maxOpenOrders(null)
                .cooldownSeconds(null)

                // advanced defaults
                .advancedControlMode(AdvancedControlMode.MANUAL)
                .active(false)
                .build();

        StrategySettings saved = save(created);
        cache.put(k, saved);
        return saved;
    }

    // =====================================================================
    // FIND LATEST (по контексту или fallback)
    // =====================================================================
    @Override
    public Optional<StrategySettings> findLatest(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {
        boolean hasExchange = exchange != null && !exchange.isBlank();
        boolean hasNetwork  = network != null;

        if (hasExchange && hasNetwork) {
            String ex = normalizeExchange(exchange);
            return repo.findTopByChatIdAndTypeAndExchangeNameAndNetworkTypeOrderByUpdatedAtDescIdDesc(
                    chatId, type, ex, network
            );
        }

        return repo.findTopByChatIdAndTypeOrderByUpdatedAtDescIdDesc(chatId, type);
    }

    // =====================================================================
    // findLatestAny(chatId, type) — без exchange/network
    // =====================================================================
    public Optional<StrategySettings> findLatestAny(Long chatId, StrategyType type) {
        if (chatId == null || type == null) return Optional.empty();
        return repo.findTopByChatIdAndTypeOrderByUpdatedAtDescIdDesc(chatId, type);
    }

    // =====================================================================
    // UPDATE RISK FROM UI
    // =====================================================================
    @Override
    @Transactional
    public void updateRiskFromUi(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            BigDecimal dailyLossLimitPct,
            BigDecimal riskPerTradePct
    ) {
        StrategySettings s = getOrCreate(chatId, type, exchange, network);

        s.setDailyLossLimitPct(validatePct(dailyLossLimitPct));
        s.setRiskPerTradePct(validatePct(riskPerTradePct));

        save(s);
    }

    // =====================================================================
    // UPDATE RISK FROM AI
    // =====================================================================
    @Override
    @Transactional
    public void updateRiskFromAi(
            long chatId,
            StrategyType type,
            String exchange,
            NetworkType network,
            BigDecimal newRiskPerTradePct
    ) {
        StrategySettings s = getOrCreate(chatId, type, exchange, network);

        if (s.getAdvancedControlMode() == AdvancedControlMode.MANUAL) {
            return;
        }

        BigDecimal incoming = validatePct(newRiskPerTradePct);
        BigDecimal current = s.getRiskPerTradePct();

        // пример политики: AI может только снижать риск (не увеличивать)
        if (current == null || (incoming != null && incoming.compareTo(current) < 0)) {
            s.setRiskPerTradePct(incoming);
            save(s);
        }
    }

    // =====================================================================
    // VALIDATION
    // =====================================================================
    private BigDecimal validatePct(BigDecimal v) {
        if (v == null) return null;

        if (v.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        if (v.compareTo(BigDecimal.valueOf(100)) > 0) {
            return BigDecimal.valueOf(100).setScale(4, RoundingMode.HALF_UP);
        }

        return v.setScale(4, RoundingMode.HALF_UP);
    }

    private String normalizeExchange(String exchange) {
        return (exchange == null || exchange.isBlank())
                ? "BINANCE"
                : exchange.trim().toUpperCase();
    }
}
