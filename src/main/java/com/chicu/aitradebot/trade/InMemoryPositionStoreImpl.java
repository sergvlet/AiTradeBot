package com.chicu.aitradebot.trade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategyPositionEntity;
import com.chicu.aitradebot.domain.enums.StrategyPositionSource;
import com.chicu.aitradebot.domain.enums.StrategyPositionStatus;
import com.chicu.aitradebot.exchange.repository.StrategyPositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class InMemoryPositionStoreImpl implements PositionStore {

    private static final String ANY_SYMBOL = "__ANY__";
    private static final long DEFAULT_SUPPRESS_MS = 21_600_000L;

    private final StrategyPositionRepository strategyPositionRepository;

    private final Map<String, PositionSnapshot> positions = new ConcurrentHashMap<>();
    private final Map<String, Instant> suppressedRestoreUntil = new ConcurrentHashMap<>();

    @Override
    public boolean isInPosition(Long chatId,
                                StrategyType type,
                                String exchange,
                                NetworkType network,
                                String symbol) {
        return getPosition(chatId, type, exchange, network, symbol).isPresent();
    }

    @Override
    @Transactional
    public void markOpened(Long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType network,
                           String symbol) {
        markOpened(chatId, type, exchange, network, symbol, null, null, null, null, null, null, null, Instant.now());
    }

    @Override
    @Transactional
    public void markOpened(Long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType network,
                           String symbol,
                           String positionUid,
                           BigDecimal entryPrice,
                           BigDecimal qty,
                           BigDecimal tp,
                           BigDecimal sl,
                           BigDecimal quoteSpent,
                           Long entryOrderId,
                           Instant openedAt) {

        if (chatId == null || type == null || exchange == null || network == null || symbol == null) {
            return;
        }

        String ex = normUpper(exchange);
        String sym = normUpper(symbol);
        String uid = (positionUid != null && !positionUid.isBlank())
                ? positionUid.trim()
                : UUID.randomUUID().toString().replace("-", "");

        String key = key(chatId, type, ex, network, sym);

        PositionSnapshot snapshot = new PositionSnapshot(
                chatId,
                type,
                ex,
                network,
                sym,
                uid,
                positiveOrNull(entryPrice),
                positiveOrNull(qty),
                positiveOrNull(tp),
                positiveOrNull(sl),
                positiveOrNull(quoteSpent),
                entryOrderId,
                openedAt != null ? openedAt : Instant.now()
        );

        positions.put(key, snapshot);
        suppressedRestoreUntil.remove(key);

        StrategyPositionEntity entity = strategyPositionRepository
                .findFirstByChatIdAndStrategyTypeAndExchangeNameAndNetworkTypeAndSymbolAndStatusInOrderByOpenedAtDesc(
                        chatId,
                        type,
                        ex,
                        network,
                        sym,
                        List.of(StrategyPositionStatus.OPEN, StrategyPositionStatus.CLOSING)
                )
                .orElseGet(() -> StrategyPositionEntity.builder()
                        .chatId(chatId)
                        .strategyType(type)
                        .exchangeName(ex)
                        .networkType(network)
                        .symbol(sym)
                        .positionUid(uid)
                        .source(StrategyPositionSource.LOCAL)
                        .status(StrategyPositionStatus.OPEN)
                        .side("BUY")
                        .openedAt(snapshot.openedAt())
                        .build());

        if (entity.getPositionUid() == null || entity.getPositionUid().isBlank()) {
            entity.setPositionUid(uid);
        }

        entity.setStatus(StrategyPositionStatus.OPEN);
        entity.setClosedAt(null);
        entity.setAvgEntryPrice(snapshot.entryPrice());
        entity.setQty(snapshot.qty());
        entity.setTpPrice(snapshot.tp());
        entity.setSlPrice(snapshot.sl());
        entity.setQuoteSpent(snapshot.quoteSpent());
        entity.setEntryOrderId(snapshot.entryOrderId());
        entity.setOpenedAt(snapshot.openedAt());
        entity.setLastExchangeSyncAt(Instant.now());

        strategyPositionRepository.save(entity);
    }

    @Override
    @Transactional
    public void markClosed(Long chatId,
                           StrategyType type,
                           String exchange,
                           NetworkType network,
                           String symbol) {
        clearPosition(chatId, type, exchange, network, symbol);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PositionSnapshot> getPosition(Long chatId,
                                                  StrategyType type,
                                                  String exchange,
                                                  NetworkType network,
                                                  String symbol) {
        if (chatId == null || type == null || exchange == null || network == null) {
            return Optional.empty();
        }

        String ex = normUpper(exchange);
        String sym = normUpper(symbol);

        if (sym == null) {
            for (Map.Entry<String, PositionSnapshot> e : positions.entrySet()) {
                if (e.getKey().startsWith(prefixKey(chatId, type, ex, network))) {
                    return Optional.ofNullable(e.getValue());
                }
            }
            return Optional.empty();
        }

        String key = key(chatId, type, ex, network, sym);
        PositionSnapshot cached = positions.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }

        if (isRestoreSuppressed(chatId, type, ex, network, sym)) {
            return Optional.empty();
        }

        Optional<StrategyPositionEntity> db = strategyPositionRepository
                .findFirstByChatIdAndStrategyTypeAndExchangeNameAndNetworkTypeAndSymbolAndStatusInOrderByOpenedAtDesc(
                        chatId,
                        type,
                        ex,
                        network,
                        sym,
                        List.of(StrategyPositionStatus.OPEN, StrategyPositionStatus.CLOSING)
                );

        if (db.isEmpty()) {
            return Optional.empty();
        }

        PositionSnapshot restored = toSnapshot(db.get());
        positions.put(key, restored);
        return Optional.of(restored);
    }

    @Override
    @Transactional
    public void clearPosition(Long chatId,
                              StrategyType type,
                              String exchange,
                              NetworkType network,
                              String symbol) {
        if (chatId == null || type == null || exchange == null || network == null) {
            return;
        }

        String ex = normUpper(exchange);
        String sym = normUpper(symbol);

        if (sym != null) {
            positions.remove(key(chatId, type, ex, network, sym));
            suppressedRestoreUntil.remove(key(chatId, type, ex, network, sym));

            strategyPositionRepository
                    .findFirstByChatIdAndStrategyTypeAndExchangeNameAndNetworkTypeAndSymbolAndStatusInOrderByOpenedAtDesc(
                            chatId,
                            type,
                            ex,
                            network,
                            sym,
                            List.of(StrategyPositionStatus.OPEN, StrategyPositionStatus.CLOSING)
                    )
                    .ifPresent(entity -> {
                        entity.setStatus(StrategyPositionStatus.CLOSED);
                        entity.setClosedAt(Instant.now());
                        entity.setLastExchangeSyncAt(Instant.now());
                        strategyPositionRepository.save(entity);
                    });
            return;
        }

        String prefix = prefixKey(chatId, type, ex, network);
        positions.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void suppressRestore(Long chatId,
                                StrategyType type,
                                String exchange,
                                NetworkType network,
                                String symbol,
                                long ttlMs,
                                String reason) {
        if (chatId == null || type == null || exchange == null || network == null || symbol == null) {
            return;
        }

        long effectiveTtlMs = ttlMs > 0 ? ttlMs : DEFAULT_SUPPRESS_MS;
        String key = key(chatId, type, normUpper(exchange), network, normUpper(symbol));

        positions.remove(key);
        suppressedRestoreUntil.put(key, Instant.now().plusMillis(effectiveTtlMs));

        log.warn("[POS] ⛔ SUPPRESS RESTORE chatId={} type={} ex={} net={} sym={} ttlMs={} reason={}",
                chatId, type, exchange, network, symbol, effectiveTtlMs, reason);
    }

    public boolean isRestoreSuppressed(Long chatId,
                                       StrategyType type,
                                       String exchange,
                                       NetworkType network,
                                       String symbol) {
        if (chatId == null || type == null || exchange == null || network == null || symbol == null) {
            return false;
        }

        String key = key(chatId, type, normUpper(exchange), network, normUpper(symbol));
        Instant until = suppressedRestoreUntil.get(key);

        if (until == null) {
            return false;
        }
        if (Instant.now().isBefore(until)) {
            return true;
        }

        suppressedRestoreUntil.remove(key);
        return false;
    }

    private PositionSnapshot toSnapshot(StrategyPositionEntity entity) {
        return new PositionSnapshot(
                entity.getChatId(),
                entity.getStrategyType(),
                entity.getExchangeName(),
                entity.getNetworkType(),
                entity.getSymbol(),
                entity.getPositionUid(),
                entity.getAvgEntryPrice(),
                entity.getQty(),
                entity.getTpPrice(),
                entity.getSlPrice(),
                entity.getQuoteSpent(),
                entity.getEntryOrderId(),
                entity.getOpenedAt()
        );
    }

    private static String key(Long chatId, StrategyType type, String ex, NetworkType net, String sym) {
        return prefixKey(chatId, type, ex, net) + ":" + (sym != null ? sym : ANY_SYMBOL);
    }

    private static String prefixKey(Long chatId, StrategyType type, String ex, NetworkType net) {
        return chatId + ":" + (type != null ? type.name() : "NA") + ":" + ex + ":" + (net != null ? net.name() : "NA");
    }

    private static String normUpper(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static BigDecimal positiveOrNull(BigDecimal v) {
        return (v != null && v.signum() > 0) ? v : null;
    }
}