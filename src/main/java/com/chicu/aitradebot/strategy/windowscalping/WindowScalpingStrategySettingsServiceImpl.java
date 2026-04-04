package com.chicu.aitradebot.strategy.windowscalping;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.events.WindowScalpingSettingsUpdatedEvent;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class WindowScalpingStrategySettingsServiceImpl implements WindowScalpingStrategySettingsService {

    private final WindowScalpingStrategySettingsRepository repo;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<StrategySettingsService> strategySettingsServiceProvider;

    @Override
    @Transactional
    public WindowScalpingStrategySettings getOrCreate(Long chatId) {
        validateChatId(chatId);

        ResolvedContext currentCtx = resolveCurrentContext(chatId).orElse(null);
        if (currentCtx != null) {
            return getOrCreate(chatId,
                    currentCtx.exchangeName(),
                    currentCtx.networkType(),
                    currentCtx.symbol(),
                    currentCtx.timeframe());
        }

        return repo.findTopByChatIdOrderByUpdatedAtDesc(chatId)
                .orElseGet(() -> createDefault(chatId, defaultContext()));
    }

    @Override
    @Transactional
    public WindowScalpingStrategySettings getOrCreate(Long chatId,
                                                      String exchangeName,
                                                      NetworkType networkType,
                                                      String symbol,
                                                      String timeframe) {
        validateChatId(chatId);

        ResolvedContext ctx = sanitizeContext(exchangeName, networkType, symbol, timeframe);

        return repo.findByChatIdAndExchangeNameAndNetworkTypeAndSymbolAndTimeframe(
                        chatId,
                        ctx.exchangeName(),
                        ctx.networkType(),
                        ctx.symbol(),
                        ctx.timeframe()
                )
                .orElseGet(() -> createDefault(chatId, ctx));
    }

    @Override
    @Transactional
    public WindowScalpingStrategySettings update(Long chatId, WindowScalpingStrategySettings incoming) {
        validateChatId(chatId);
        if (incoming == null) {
            throw new IllegalArgumentException("incoming is null");
        }

        ResolvedContext ctx = resolveContextForUpdate(chatId, incoming);
        return update(
                chatId,
                ctx.exchangeName(),
                ctx.networkType(),
                ctx.symbol(),
                ctx.timeframe(),
                incoming
        );
    }

    @Override
    @Transactional
    public WindowScalpingStrategySettings update(Long chatId,
                                                 String exchangeName,
                                                 NetworkType networkType,
                                                 String symbol,
                                                 String timeframe,
                                                 WindowScalpingStrategySettings incoming) {
        validateChatId(chatId);
        if (incoming == null) {
            throw new IllegalArgumentException("incoming is null");
        }

        ResolvedContext ctx = sanitizeContext(exchangeName, networkType, symbol, timeframe);
        WindowScalpingStrategySettings cur = getOrCreate(
                chatId,
                ctx.exchangeName(),
                ctx.networkType(),
                ctx.symbol(),
                ctx.timeframe()
        );

        boolean patchMode = (incoming.getId() == null);

        WindowScalpingStrategySettings defaults = WindowScalpingStrategySettings.builder()
                .chatId(chatId)
                .exchangeName(ctx.exchangeName())
                .networkType(ctx.networkType())
                .symbol(ctx.symbol())
                .timeframe(ctx.timeframe())
                .build();

        boolean changed = false;

        if (!Objects.equals(cur.getChatId(), chatId)) {
            cur.setChatId(chatId);
            changed = true;
        }
        if (!Objects.equals(cur.getExchangeName(), ctx.exchangeName())) {
            cur.setExchangeName(ctx.exchangeName());
            changed = true;
        }
        if (cur.getNetworkType() != ctx.networkType()) {
            cur.setNetworkType(ctx.networkType());
            changed = true;
        }
        if (!Objects.equals(cur.getSymbol(), ctx.symbol())) {
            cur.setSymbol(ctx.symbol());
            changed = true;
        }
        if (!Objects.equals(cur.getTimeframe(), ctx.timeframe())) {
            cur.setTimeframe(ctx.timeframe());
            changed = true;
        }

        changed |= applyBigDecimal(incoming.getTakeProfitPct(), defaults.getTakeProfitPct(), cur.getTakeProfitPct(), patchMode, cur::setTakeProfitPct);
        changed |= applyBigDecimal(incoming.getStopLossPct(), defaults.getStopLossPct(), cur.getStopLossPct(), patchMode, cur::setStopLossPct);

        changed |= applyBoolean(incoming.getAutoTpSlEnabled(), defaults.getAutoTpSlEnabled(), cur.getAutoTpSlEnabled(), patchMode, cur::setAutoTpSlEnabled);
        changed |= applyBigDecimal(incoming.getAutoSlFromRangeFactor(), defaults.getAutoSlFromRangeFactor(), cur.getAutoSlFromRangeFactor(), patchMode, cur::setAutoSlFromRangeFactor);
        changed |= applyBigDecimal(incoming.getAutoTpFromRangeFactor(), defaults.getAutoTpFromRangeFactor(), cur.getAutoTpFromRangeFactor(), patchMode, cur::setAutoTpFromRangeFactor);
        changed |= applyBigDecimal(incoming.getAutoMinRiskReward(), defaults.getAutoMinRiskReward(), cur.getAutoMinRiskReward(), patchMode, cur::setAutoMinRiskReward);
        changed |= applyBigDecimal(incoming.getAutoSlMinPct(), defaults.getAutoSlMinPct(), cur.getAutoSlMinPct(), patchMode, cur::setAutoSlMinPct);
        changed |= applyBigDecimal(incoming.getAutoSlMaxPct(), defaults.getAutoSlMaxPct(), cur.getAutoSlMaxPct(), patchMode, cur::setAutoSlMaxPct);
        changed |= applyBigDecimal(incoming.getAutoTpMinPct(), defaults.getAutoTpMinPct(), cur.getAutoTpMinPct(), patchMode, cur::setAutoTpMinPct);
        changed |= applyBigDecimal(incoming.getAutoTpMaxPct(), defaults.getAutoTpMaxPct(), cur.getAutoTpMaxPct(), patchMode, cur::setAutoTpMaxPct);
        changed |= applyBigDecimal(incoming.getAutoTpMlBoostFactor(), defaults.getAutoTpMlBoostFactor(), cur.getAutoTpMlBoostFactor(), patchMode, cur::setAutoTpMlBoostFactor);
        changed |= applyBigDecimal(incoming.getAutoTpWeakSignalFactor(), defaults.getAutoTpWeakSignalFactor(), cur.getAutoTpWeakSignalFactor(), patchMode, cur::setAutoTpWeakSignalFactor);

        Integer ws = incoming.getWindowSize();
        if (ws != null && ws >= 5) {
            if (!patchMode || !Objects.equals(ws, defaults.getWindowSize())) {
                if (!Objects.equals(ws, cur.getWindowSize())) {
                    cur.setWindowSize(ws);
                    changed = true;
                }
            }
        }

        changed |= applyDouble(incoming.getEntryFromLowPct(), defaults.getEntryFromLowPct(), cur.getEntryFromLowPct(), patchMode, 0.0, 100.0, cur::setEntryFromLowPct);
        changed |= applyDouble(incoming.getEntryFromHighPct(), defaults.getEntryFromHighPct(), cur.getEntryFromHighPct(), patchMode, 0.0, 100.0, cur::setEntryFromHighPct);
        changed |= applyDouble(incoming.getMinRangePct(), defaults.getMinRangePct(), cur.getMinRangePct(), patchMode, 0.0, 100.0, cur::setMinRangePct);
        changed |= applyDouble(incoming.getMaxSpreadPct(), defaults.getMaxSpreadPct(), cur.getMaxSpreadPct(), patchMode, 0.0, 100.0, cur::setMaxSpreadPct);

        normalizeAutoBounds(cur);

        if (!changed) {
            return cur;
        }

        WindowScalpingStrategySettings saved = repo.saveAndFlush(cur);

        publishAfterCommit(new WindowScalpingSettingsUpdatedEvent(
                chatId,
                "update:" + ctx.exchangeName() + ':' + ctx.networkType() + ':' + ctx.symbol() + ':' + ctx.timeframe()
        ));

        log.info("✅ WINDOW_SCALPING settings updated (chatId={}, id={}, ex={}, net={}, sym={}, tf={}, tpPct={}, slPct={}, autoTpSl={}, slFactor={}, tpFactor={}, minRR={}, slMinPct={}, slMaxPct={}, tpMinPct={}, tpMaxPct={}, tpMlBoost={}, tpWeakFactor={}, windowSize={}, minRangePct={}, entryLowPct={}, entryHighPct={}, maxSpreadPct={})",
                chatId,
                saved.getId(),
                saved.getExchangeName(),
                saved.getNetworkType(),
                saved.getSymbol(),
                saved.getTimeframe(),
                saved.getTakeProfitPct(),
                saved.getStopLossPct(),
                saved.getAutoTpSlEnabled(),
                saved.getAutoSlFromRangeFactor(),
                saved.getAutoTpFromRangeFactor(),
                saved.getAutoMinRiskReward(),
                saved.getAutoSlMinPct(),
                saved.getAutoSlMaxPct(),
                saved.getAutoTpMinPct(),
                saved.getAutoTpMaxPct(),
                saved.getAutoTpMlBoostFactor(),
                saved.getAutoTpWeakSignalFactor(),
                saved.getWindowSize(),
                saved.getMinRangePct(),
                saved.getEntryFromLowPct(),
                saved.getEntryFromHighPct(),
                saved.getMaxSpreadPct()
        );

        return saved;
    }

    public WindowScalpingStrategySettingsRepository getRepository() {
        return repo;
    }

    @Override
    @Transactional(readOnly = true)
    public Long getVersion(Long chatId) {
        validateChatId(chatId);

        ResolvedContext ctx = resolveCurrentContext(chatId).orElse(null);
        if (ctx != null) {
            return getVersion(chatId, ctx.exchangeName(), ctx.networkType(), ctx.symbol(), ctx.timeframe());
        }

        return repo.findTopByChatIdOrderByUpdatedAtDesc(chatId)
                .map(WindowScalpingStrategySettings::getVersion)
                .map(Integer::longValue)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Long getVersion(Long chatId,
                           String exchangeName,
                           NetworkType networkType,
                           String symbol,
                           String timeframe) {
        validateChatId(chatId);

        ResolvedContext ctx = sanitizeContext(exchangeName, networkType, symbol, timeframe);
        Integer version = repo.findVersionByContext(
                chatId,
                ctx.exchangeName(),
                ctx.networkType(),
                ctx.symbol(),
                ctx.timeframe()
        );
        return version != null ? version.longValue() : null;
    }

    private WindowScalpingStrategySettings createDefault(Long chatId, ResolvedContext ctx) {
        try {
            WindowScalpingStrategySettings def = WindowScalpingStrategySettings.builder()
                    .chatId(chatId)
                    .exchangeName(ctx.exchangeName())
                    .networkType(ctx.networkType())
                    .symbol(ctx.symbol())
                    .timeframe(ctx.timeframe())
                    .build();

            WindowScalpingStrategySettings saved = repo.saveAndFlush(def);

            log.info("🆕 WINDOW_SCALPING settings created (chatId={}, id={}, ex={}, net={}, sym={}, tf={})",
                    chatId,
                    saved.getId(),
                    saved.getExchangeName(),
                    saved.getNetworkType(),
                    saved.getSymbol(),
                    saved.getTimeframe());

            publishAfterCommit(new WindowScalpingSettingsUpdatedEvent(
                    chatId,
                    "create:" + ctx.exchangeName() + ':' + ctx.networkType() + ':' + ctx.symbol() + ':' + ctx.timeframe()
            ));

            return saved;

        } catch (DataIntegrityViolationException dup) {
            return repo.findByChatIdAndExchangeNameAndNetworkTypeAndSymbolAndTimeframe(
                            chatId,
                            ctx.exchangeName(),
                            ctx.networkType(),
                            ctx.symbol(),
                            ctx.timeframe()
                    )
                    .orElseThrow(() -> dup);
        }
    }

    private Optional<ResolvedContext> resolveCurrentContext(Long chatId) {
        try {
            StrategySettingsService strategySettingsService = strategySettingsServiceProvider.getIfAvailable();
            if (strategySettingsService == null) {
                return Optional.empty();
            }

            StrategySettings ss = strategySettingsService.getSettings(chatId, StrategyType.WINDOW_SCALPING);
            if (ss == null) {
                ss = strategySettingsService.getOrCreate(chatId, StrategyType.WINDOW_SCALPING);
            }
            if (ss == null) {
                return Optional.empty();
            }

            return Optional.of(sanitizeContext(
                    ss.getExchangeName(),
                    ss.getNetworkType(),
                    ss.getSymbol(),
                    ss.getTimeframe()
            ));
        } catch (Exception e) {
            log.debug("⚠️ WINDOW_SCALPING resolveCurrentContext failed chatId={} err={}", chatId, e.toString());
            return Optional.empty();
        }
    }

    private Optional<ResolvedContext> resolveLatestStoredContext(Long chatId) {
        return repo.findTopByChatIdOrderByUpdatedAtDesc(chatId)
                .map(this::contextOf);
    }

    /**
     * Главный фикс:
     * если приходит patch-объект через builder() без явного контекста,
     * @Builder.Default подставляет BINANCE/TESTNET/BTCUSDT/1m.
     * Такой “контекст” нельзя считать явным выбором пользователя.
     */
    private ResolvedContext resolveContextForUpdate(Long chatId, WindowScalpingStrategySettings incoming) {
        if (incoming.getId() != null) {
            WindowScalpingStrategySettings byId = repo.findByIdAndChatId(incoming.getId(), chatId).orElse(null);
            if (byId != null) {
                return contextOf(byId);
            }
        }

        ResolvedContext currentCtx = resolveCurrentContext(chatId).orElse(null);
        ResolvedContext latestCtx = resolveLatestStoredContext(chatId).orElse(null);
        ResolvedContext incomingCtx = extractIncomingContextOrNull(incoming);

        if (incomingCtx != null && !looksLikeImplicitBuilderDefault(incomingCtx, currentCtx, latestCtx)) {
            return incomingCtx;
        }

        if (incomingCtx != null && looksLikeImplicitBuilderDefault(incomingCtx, currentCtx, latestCtx)) {
            ResolvedContext chosen = (currentCtx != null ? currentCtx : latestCtx);
            if (chosen != null) {
                log.warn("⚠️ WINDOW_SCALPING patch-context fallback chatId={} incomingCtx={}/{}/{}/{} -> using {}/{}/{}/{}",
                        chatId,
                        incomingCtx.exchangeName(), incomingCtx.networkType(), incomingCtx.symbol(), incomingCtx.timeframe(),
                        chosen.exchangeName(), chosen.networkType(), chosen.symbol(), chosen.timeframe());
                return chosen;
            }
        }

        if (currentCtx != null) {
            return currentCtx;
        }
        if (latestCtx != null) {
            return latestCtx;
        }
        if (incomingCtx != null) {
            return incomingCtx;
        }

        return defaultContext();
    }

    private ResolvedContext extractIncomingContextOrNull(WindowScalpingStrategySettings incoming) {
        if (incoming == null) {
            return null;
        }

        String ex = trimToNull(incoming.getExchangeName());
        NetworkType net = incoming.getNetworkType();
        String sym = trimToNull(incoming.getSymbol());
        String tf = trimToNull(incoming.getTimeframe());

        if (ex == null || net == null || sym == null || tf == null) {
            return null;
        }

        return sanitizeContext(ex, net, sym, tf);
    }

    private boolean looksLikeImplicitBuilderDefault(ResolvedContext incomingCtx,
                                                    ResolvedContext currentCtx,
                                                    ResolvedContext latestCtx) {
        if (incomingCtx == null) {
            return false;
        }

        ResolvedContext def = defaultContext();
        boolean equalsDefault = sameContext(incomingCtx, def);
        if (!equalsDefault) {
            return false;
        }

        ResolvedContext reference = (currentCtx != null ? currentCtx : latestCtx);
        return reference != null && !sameContext(reference, def);
    }

    private ResolvedContext contextOf(WindowScalpingStrategySettings s) {
        return sanitizeContext(
                s.getExchangeName(),
                s.getNetworkType(),
                s.getSymbol(),
                s.getTimeframe()
        );
    }

    private boolean sameContext(ResolvedContext a, ResolvedContext b) {
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.exchangeName(), b.exchangeName())
                && a.networkType() == b.networkType()
                && Objects.equals(a.symbol(), b.symbol())
                && Objects.equals(a.timeframe(), b.timeframe());
    }

    private ResolvedContext sanitizeContext(String exchangeName,
                                            NetworkType networkType,
                                            String symbol,
                                            String timeframe) {
        String ex = normalizeExchange(exchangeName);
        NetworkType net = (networkType != null ? networkType : WindowScalpingStrategySettings.DEFAULT_NETWORK);
        String sym = normalizeSymbol(symbol);
        String tf = normalizeTimeframe(timeframe);
        return new ResolvedContext(ex, net, sym, tf);
    }

    private ResolvedContext defaultContext() {
        return new ResolvedContext(
                WindowScalpingStrategySettings.DEFAULT_EXCHANGE,
                WindowScalpingStrategySettings.DEFAULT_NETWORK,
                WindowScalpingStrategySettings.DEFAULT_SYMBOL,
                WindowScalpingStrategySettings.DEFAULT_TIMEFRAME
        );
    }

    private void validateChatId(Long chatId) {
        if (chatId == null || chatId <= 0) {
            throw new IllegalArgumentException("chatId must be positive");
        }
    }

    private void normalizeAutoBounds(WindowScalpingStrategySettings cur) {
        if (cur == null) {
            return;
        }

        BigDecimal slMin = positiveOrDefault(cur.getAutoSlMinPct(), new BigDecimal("0.04"));
        BigDecimal slMax = positiveOrDefault(cur.getAutoSlMaxPct(), new BigDecimal("0.18"));
        if (slMax.compareTo(slMin) < 0) {
            slMax = slMin;
        }

        BigDecimal tpMin = positiveOrDefault(cur.getAutoTpMinPct(), new BigDecimal("0.10"));
        BigDecimal tpMax = positiveOrDefault(cur.getAutoTpMaxPct(), new BigDecimal("0.80"));
        if (tpMax.compareTo(tpMin) < 0) {
            tpMax = tpMin;
        }

        BigDecimal minRr = positiveOrDefault(cur.getAutoMinRiskReward(), new BigDecimal("2.40"));
        BigDecimal minTpByRr = slMin.multiply(minRr);
        if (tpMin.compareTo(minTpByRr) < 0) {
            tpMin = minTpByRr.setScale(8, RoundingMode.HALF_UP);
            if (tpMax.compareTo(tpMin) < 0) {
                tpMax = tpMin;
            }
        }

        cur.setAutoSlMinPct(slMin.setScale(8, RoundingMode.HALF_UP));
        cur.setAutoSlMaxPct(slMax.setScale(8, RoundingMode.HALF_UP));
        cur.setAutoTpMinPct(tpMin.setScale(8, RoundingMode.HALF_UP));
        cur.setAutoTpMaxPct(tpMax.setScale(8, RoundingMode.HALF_UP));
    }

    private void publishAfterCommit(Object event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            events.publishEvent(event);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    events.publishEvent(event);
                } catch (Exception e) {
                    log.warn("⚠️ publishAfterCommit failed: {}", e.toString());
                }
            }
        });
    }

    private boolean applyBigDecimal(BigDecimal incoming,
                                    BigDecimal defaultValue,
                                    BigDecimal currentValue,
                                    boolean patchMode,
                                    Consumer<BigDecimal> setter) {
        if (incoming == null || incoming.signum() <= 0) {
            return false;
        }
        if (patchMode && bdEquals(incoming, defaultValue)) {
            return false;
        }
        if (bdEquals(incoming, currentValue)) {
            return false;
        }
        setter.accept(incoming.setScale(8, RoundingMode.HALF_UP));
        return true;
    }

    private boolean applyBoolean(Boolean incoming,
                                 Boolean defaultValue,
                                 Boolean currentValue,
                                 boolean patchMode,
                                 Consumer<Boolean> setter) {
        if (incoming == null) {
            return false;
        }
        if (patchMode && Objects.equals(incoming, defaultValue)) {
            return false;
        }
        if (Objects.equals(incoming, currentValue)) {
            return false;
        }
        setter.accept(incoming);
        return true;
    }

    private boolean applyDouble(Double incoming,
                                Double defaultValue,
                                Double currentValue,
                                boolean patchMode,
                                double min,
                                double max,
                                Consumer<Double> setter) {
        if (incoming == null) {
            return false;
        }

        double v = clamp(incoming, min, max);
        if (patchMode && dblEquals(v, safeD(defaultValue))) {
            return false;
        }
        if (dblEquals(v, safeD(currentValue))) {
            return false;
        }

        setter.accept(v);
        return true;
    }

    private static BigDecimal positiveOrDefault(BigDecimal value, BigDecimal def) {
        return (value != null && value.signum() > 0) ? value : def;
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static boolean dblEquals(double a, double b) {
        return Math.abs(a - b) < 1e-12;
    }

    private static double safeD(Double value) {
        return value == null ? 0.0 : value;
    }

    private static boolean bdEquals(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.compareTo(b) == 0;
    }

    private static String normalizeExchange(String value) {
        if (isBlank(value)) {
            return WindowScalpingStrategySettings.DEFAULT_EXCHANGE;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeSymbol(String value) {
        if (isBlank(value)) {
            return WindowScalpingStrategySettings.DEFAULT_SYMBOL;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeTimeframe(String value) {
        if (isBlank(value)) {
            return WindowScalpingStrategySettings.DEFAULT_TIMEFRAME;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ResolvedContext(String exchangeName,
                                   NetworkType networkType,
                                   String symbol,
                                   String timeframe) {
    }
}