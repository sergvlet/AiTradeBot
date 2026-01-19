package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.account.AccountBalanceService;
import com.chicu.aitradebot.account.AccountBalanceSnapshot;
import com.chicu.aitradebot.ai.tuning.AutoTunerOrchestrator;
import com.chicu.aitradebot.ai.tuning.TuningRequest;
import com.chicu.aitradebot.ai.tuning.TuningResult;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/strategy/settings")
public class StrategySettingsApiController {

    private final StrategySettingsService strategySettingsService;
    private final AccountBalanceService accountBalanceService;
    private final AutoTunerOrchestrator autoTunerOrchestrator;

    // =========================================================
    // 1) POST /asset — смена выбранного актива + snapshot
    // =========================================================
    @PostMapping("/asset")
    public ResponseEntity<?> changeAsset(
            @RequestParam long chatId,
            @RequestParam StrategyType type,
            @RequestParam String exchange,
            @RequestParam NetworkType network,
            @RequestParam String asset
    ) {
        String ex = normalizeExchangeOrThrow(exchange);
        requireNonNull(network, "network is required");

        StrategySettings s = strategySettingsService.getOrCreate(chatId, type, ex, network);
        s.setAccountAsset(normalizeAssetOrNull(asset));
        strategySettingsService.save(s);

        return ResponseEntity.ok(accountBalanceService.getSnapshot(chatId, type, ex, network));
    }

    // =========================================================
    // 2) GET /balance — snapshot
    // =========================================================
    @GetMapping("/balance")
    public ResponseEntity<?> getBalance(
            @RequestParam long chatId,
            @RequestParam StrategyType type,
            @RequestParam String exchange,
            @RequestParam NetworkType network,
            @RequestParam(required = false) String asset
    ) {
        String ex = normalizeExchangeOrThrow(exchange);
        requireNonNull(network, "network is required");

        if (asset != null) {
            StrategySettings s = strategySettingsService.getOrCreate(chatId, type, ex, network);
            s.setAccountAsset(normalizeAssetOrNull(asset));
            strategySettingsService.save(s);
        }

        return ResponseEntity.ok(accountBalanceService.getSnapshot(chatId, type, ex, network));
    }

    // =========================================================
    // 3) POST /autosave — автосейв StrategySettings + snapshot
    // + автозапуск тюнинга при изменении trade-контекста в HYBRID/AI
    // =========================================================
    @PostMapping("/autosave")
    public ResponseEntity<?> autosave(@RequestBody AutosaveRequest req) {

        if (req == null) return badRequest("request body is null");
        if (req.getChatId() == null) return badRequest("chatId is required");
        if (req.getType() == null) return badRequest("type is required");

        final String ex;
        try {
            ex = normalizeExchangeOrThrow(req.getExchange());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        if (req.getNetwork() == null) return badRequest("network is required");

        // ✅ детект "нового контекста" (сменили биржу/сеть → записи ещё нет)
        StrategySettings existed = strategySettingsService.getSettings(req.getChatId(), req.getType(), ex, req.getNetwork());
        boolean ctxWasMissing = (existed == null);

        StrategySettings s = strategySettingsService.getOrCreate(req.getChatId(), req.getType(), ex, req.getNetwork());

        // "до" (для триггеров тюнинга)
        AdvancedControlMode modeBefore = s.getAdvancedControlMode();
        String symbolBefore = s.getSymbol();
        String tfBefore = s.getTimeframe();
        Integer candlesBefore = s.getCachedCandlesLimit();

        // ---------------------------------------------------------
        // MODE — сохраняем, если пришёл валидный
        // ---------------------------------------------------------
        AdvancedControlMode parsedMode = parseEnumOrNull(AdvancedControlMode.class, req.getAdvancedControlMode());
        if (parsedMode != null && parsedMode != modeBefore) {
            // лог только при реальном изменении
            log.info("🧠 MODE change {} -> {} (chatId={}, type={}, ex={}, net={})",
                    modeBefore, parsedMode, req.getChatId(), req.getType(), ex, req.getNetwork());
            s.setAdvancedControlMode(parsedMode);
        }

        String scope = normalizeScope(req.getScope());
        boolean applyAll = scope.isEmpty();
        boolean applyGeneral = applyAll || scope.equals("general");
        boolean applyRisk = applyAll || scope.equals("risk");
        boolean applyTrade = applyAll || scope.equals("trade");

        // -------------------------
        // GENERAL
        // -------------------------
        if (applyGeneral) {

            if (req.getAccountAsset() != null) {
                s.setAccountAsset(normalizeAssetOrNull(req.getAccountAsset()));
            }

            if (req.getMaxExposureUsd() != null) {
                s.setMaxExposureUsd(validateMoneyOrNull(parseBdOrNull(req.getMaxExposureUsd())));
            }
            if (req.getMaxExposurePct() != null) {
                s.setMaxExposurePct(validatePctOrNull(parseBdOrNull(req.getMaxExposurePct())));
            }
            if (req.getDailyLossLimitPct() != null) {
                s.setDailyLossLimitPct(validatePctOrNull(parseBdOrNull(req.getDailyLossLimitPct())));
            }

            if (req.getReinvestProfit() != null) {
                s.setReinvestProfit(req.getReinvestProfit());
            }
        }

        // -------------------------
        // TRADE
        // -------------------------
        if (applyTrade) {

            if (req.getSymbol() != null) {
                String sym = normalizeSymbolOrNull(req.getSymbol());
                if (sym != null) s.setSymbol(sym);
            }

            if (req.getTimeframe() != null) {
                String tf = normalizeTimeframeOrNull(req.getTimeframe());
                if (tf != null) s.setTimeframe(tf);
            }

            if (req.getCachedCandlesLimit() != null) {
                Integer candles = parseIntOrNull(req.getCachedCandlesLimit());
                if (candles != null) {
                    candles = Math.max(50, candles);
                    s.setCachedCandlesLimit(candles);
                }
            }
        }

        // -------------------------
        // RISK
        // -------------------------
        if (applyRisk) {

            if (req.getRiskPerTradePct() != null) {
                s.setRiskPerTradePct(validatePctOrNull(parseBdOrNull(req.getRiskPerTradePct())));
            }

            if (req.getMinRiskReward() != null) {
                s.setMinRiskReward(validatePositiveOrNull(parseBdOrNull(req.getMinRiskReward())));
            }

            if (req.getLeverage() != null) {
                Integer lev = parseIntOrNull(req.getLeverage());
                if (lev != null) s.setLeverage(Math.max(1, lev));
            }

            if (req.getAllowAveraging() != null) {
                s.setAllowAveraging(req.getAllowAveraging());
            }

            if (req.getCooldownSeconds() != null) {
                s.setCooldownSeconds(toNullablePositive(parseIntOrNull(req.getCooldownSeconds())));
            }

            if (req.getMaxTradesPerDay() != null) {
                s.setMaxTradesPerDay(toNullablePositive(parseIntOrNull(req.getMaxTradesPerDay())));
            }

            if (req.getMaxOpenOrders() != null) {
                s.setMaxOpenOrders(toNullablePositive(parseIntOrNull(req.getMaxOpenOrders())));
            }

            if (req.getMaxDrawdownPct() != null) {
                BigDecimal v = validatePctOrNull(parseBdOrNull(req.getMaxDrawdownPct()));
                s.setMaxDrawdownPct(v);
                if (v != null) s.setMaxDrawdownUsd(null);
            }
            if (req.getMaxDrawdownUsd() != null) {
                BigDecimal v = validateMoneyOrNull(parseBdOrNull(req.getMaxDrawdownUsd()));
                s.setMaxDrawdownUsd(v);
                if (v != null) s.setMaxDrawdownPct(null);
            }

            if (req.getMaxPositionPct() != null) {
                BigDecimal v = validatePctOrNull(parseBdOrNull(req.getMaxPositionPct()));
                s.setMaxPositionPct(v);
                if (v != null) s.setMaxPositionUsd(null);
            }
            if (req.getMaxPositionUsd() != null) {
                BigDecimal v = validateMoneyOrNull(parseBdOrNull(req.getMaxPositionUsd()));
                s.setMaxPositionUsd(v);
                if (v != null) s.setMaxPositionPct(null);
            }

            if (req.getCooldownAfterLossSeconds() != null) {
                s.setCooldownAfterLossSeconds(toNullablePositive(parseIntOrNull(req.getCooldownAfterLossSeconds())));
            }
            if (req.getMaxConsecutiveLosses() != null) {
                s.setMaxConsecutiveLosses(toNullablePositive(parseIntOrNull(req.getMaxConsecutiveLosses())));
            }
        }

        StrategySettings saved = strategySettingsService.save(s);
        AdvancedControlMode modeAfter = saved.getAdvancedControlMode();

        // =========================================================
        // ✅ АВТО-ТЮНИНГ
        // Триггеры:
        // - symbol/timeframe/candlesLimit изменились
        // - новый контекст (объект для ex/net появился впервые)
        // - режим стал HYBRID/AI (переключили с MANUAL)
        // =========================================================
        boolean modeBecameHybridOrAi =
                (modeBefore != modeAfter)
                        && modeAfter != null
                        && modeAfter != AdvancedControlMode.MANUAL;

        boolean tradeChanged =
                !Objects.equals(symbolBefore, saved.getSymbol())
                        || !Objects.equals(tfBefore, saved.getTimeframe())
                        || !Objects.equals(candlesBefore, saved.getCachedCandlesLimit());

        boolean shouldTune =
                modeAfter != null
                        && modeAfter != AdvancedControlMode.MANUAL
                        && (modeBecameHybridOrAi || tradeChanged || ctxWasMissing);

        TuningResult tuningResult = null;

        if (shouldTune) {
            // лог 1 строка, без спама
            log.info("🧠 TUNE trigger chatId={} type={} ex={} net={} sym={} tf={} candles={} reason={}",
                    saved.getChatId(), saved.getType(), ex, saved.getNetworkType(),
                    saved.getSymbol(), saved.getTimeframe(), saved.getCachedCandlesLimit(),
                    ctxWasMissing ? "CTX_MISSING" : (modeBecameHybridOrAi ? "MODE_SWITCH" : "TRADE_CHANGED")
            );

            try {
                tuningResult = autoTunerOrchestrator.tune(
                        TuningRequest.builder()
                                .chatId(saved.getChatId())
                                .strategyType(saved.getType())
                                .exchange(ex)
                                .network(saved.getNetworkType())
                                .symbol(saved.getSymbol())
                                .timeframe(saved.getTimeframe())
                                .candlesLimit(saved.getCachedCandlesLimit())
                                .reason("AUTOSAVE_TRIGGER")
                                .build()
                );
            } catch (Exception e) {
                log.warn("🧠 auto-tune failed: {}", e.getMessage());
            }
        }

        // перечитываем после тюнинга (чтобы UI сразу получил mlConfidence/totalProfit)
        StrategySettings refreshed = strategySettingsService.getOrCreate(req.getChatId(), req.getType(), ex, req.getNetwork());

        AccountBalanceSnapshot balanceSnap = accountBalanceService.getSnapshot(req.getChatId(), req.getType(), ex, req.getNetwork());
        StrategySettingsSnapshot settingsSnap = StrategySettingsSnapshot.from(refreshed);

        return ResponseEntity.ok(
                AutosaveResponse.builder()
                        .ok(true)
                        .savedAt(nowHHmmss())

                        .snapshot(balanceSnap)
                        .settingsSnapshot(settingsSnap)

                        .advancedControlMode(refreshed.getAdvancedControlMode() != null ? refreshed.getAdvancedControlMode().name() : null)
                        .accountAsset(refreshed.getAccountAsset())
                        .symbol(refreshed.getSymbol())
                        .timeframe(refreshed.getTimeframe())
                        .cachedCandlesLimit(refreshed.getCachedCandlesLimit())

                        .mlConfidence(refreshed.getMlConfidence())
                        .totalProfitPct(refreshed.getTotalProfitPct())

                        .tuningApplied(tuningResult != null && tuningResult.applied())
                        .tuningReason(tuningResult != null ? tuningResult.reason() : null)

                        .id(refreshed.getId())
                        .chatId(refreshed.getChatId())
                        .type(refreshed.getType() != null ? refreshed.getType().name() : null)
                        .exchange(refreshed.getExchangeName())
                        .network(refreshed.getNetworkType() != null ? refreshed.getNetworkType().name() : null)
                        .build()
        );
    }

    // =========================================================
    // 4) POST /apply — явное применение режима HYBRID/AI (тюнинг)
    // =========================================================
    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody ApplyRequest req) {

        if (req == null) return badRequest("request body is null");
        if (req.getChatId() == null) return badRequest("chatId is required");
        if (req.getType() == null) return badRequest("type is required");

        final String ex;
        try {
            ex = normalizeExchangeOrThrow(req.getExchange());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        if (req.getNetwork() == null) return badRequest("network is required");

        StrategySettings s = strategySettingsService.getOrCreate(req.getChatId(), req.getType(), ex, req.getNetwork());

        AdvancedControlMode incoming = parseEnumOrNull(AdvancedControlMode.class, req.getAdvancedControlMode());
        if (incoming != null && incoming != s.getAdvancedControlMode()) {
            log.info("🧠 APPLY MODE change {} -> {} (chatId={}, type={}, ex={}, net={})",
                    s.getAdvancedControlMode(), incoming, req.getChatId(), req.getType(), ex, req.getNetwork());
            s.setAdvancedControlMode(incoming);
            s = strategySettingsService.save(s);
        }

        AdvancedControlMode mode = s.getAdvancedControlMode();
        if (mode == null || mode == AdvancedControlMode.MANUAL) {
            return ResponseEntity.ok(
                    ApplyResponse.builder()
                            .applied(false)
                            .reason("MANUAL: тюнинг не выполнялся")
                            .build()
            );
        }

        TuningResult tr = autoTunerOrchestrator.tune(
                TuningRequest.builder()
                        .chatId(req.getChatId())
                        .strategyType(req.getType())
                        .exchange(ex)
                        .network(req.getNetwork())
                        .symbol(s.getSymbol())
                        .timeframe(s.getTimeframe())
                        .candlesLimit(s.getCachedCandlesLimit())
                        .reason(req.getReason())
                        .build()
        );

        StrategySettings refreshed = strategySettingsService.getOrCreate(req.getChatId(), req.getType(), ex, req.getNetwork());

        return ResponseEntity.ok(
                ApplyResponse.builder()
                        .applied(tr != null && tr.applied())
                        .reason(tr != null ? tr.reason() : "tuningResult=null")
                        .modelVersion(tr != null ? tr.modelVersion() : null)
                        .scoreBefore(tr != null ? tr.scoreBefore() : null)
                        .scoreAfter(tr != null ? tr.scoreAfter() : null)
                        .oldParams(tr != null ? tr.oldParams() : null)
                        .newParams(tr != null ? tr.newParams() : null)

                        .mlConfidence(refreshed.getMlConfidence())
                        .totalProfitPct(refreshed.getTotalProfitPct())
                        .settingsSnapshot(StrategySettingsSnapshot.from(refreshed))
                        .build()
        );
    }

    // =========================================================
    // DTO
    // =========================================================

    @Data
    public static class ApplyRequest {
        private Long chatId;
        private StrategyType type;
        private String exchange;
        private NetworkType network;
        private String advancedControlMode;
        private String reason;
    }

    @Data
    @lombok.Builder
    public static class ApplyResponse {
        private boolean applied;
        private String reason;

        private String modelVersion;
        private BigDecimal scoreBefore;
        private BigDecimal scoreAfter;

        private Map<String, Object> oldParams;
        private Map<String, Object> newParams;

        private BigDecimal mlConfidence;
        private BigDecimal totalProfitPct;
        private StrategySettingsSnapshot settingsSnapshot;
    }

    @Data
    public static class AutosaveRequest {
        private Long chatId;
        private StrategyType type;
        private String exchange;
        private NetworkType network;
        private String scope;

        // general
        private String advancedControlMode;
        private String accountAsset;

        private String maxExposureUsd;
        private String maxExposurePct;
        private String dailyLossLimitPct;

        private Boolean reinvestProfit;

        // trade
        private String symbol;
        private String timeframe;
        private String cachedCandlesLimit;

        // risk
        private String riskPerTradePct;
        private String minRiskReward;
        private String leverage;

        private Boolean allowAveraging;
        private String cooldownSeconds;
        private String maxTradesPerDay;

        private String maxDrawdownPct;
        private String maxDrawdownUsd;

        private String maxPositionPct;
        private String maxPositionUsd;

        private String cooldownAfterLossSeconds;
        private String maxConsecutiveLosses;

        private String maxOpenOrders;
    }

    @Data
    @lombok.Builder
    public static class AutosaveResponse {
        private boolean ok;
        private String savedAt;

        private AccountBalanceSnapshot snapshot;
        private StrategySettingsSnapshot settingsSnapshot;

        private String advancedControlMode;
        private String accountAsset;
        private String symbol;
        private String timeframe;
        private Integer cachedCandlesLimit;

        private BigDecimal mlConfidence;
        private BigDecimal totalProfitPct;

        private Boolean tuningApplied;
        private String tuningReason;

        private Long id;
        private Long chatId;
        private String type;
        private String exchange;
        private String network;
    }

    @Data
    @lombok.Builder
    public static class StrategySettingsSnapshot {
        private Long id;
        private Long chatId;
        private String type;
        private String exchange;
        private String network;

        private String advancedControlMode;

        private String accountAsset;
        private String symbol;
        private String timeframe;
        private Integer cachedCandlesLimit;

        private BigDecimal maxExposureUsd;
        private BigDecimal maxExposurePct;
        private BigDecimal dailyLossLimitPct;

        private Boolean reinvestProfit;

        private BigDecimal mlConfidence;
        private BigDecimal totalProfitPct;

        public static StrategySettingsSnapshot from(StrategySettings s) {
            if (s == null) return null;
            return StrategySettingsSnapshot.builder()
                    .id(s.getId())
                    .chatId(s.getChatId())
                    .type(s.getType() != null ? s.getType().name() : null)
                    .exchange(s.getExchangeName())
                    .network(s.getNetworkType() != null ? s.getNetworkType().name() : null)

                    .advancedControlMode(s.getAdvancedControlMode() != null ? s.getAdvancedControlMode().name() : null)

                    .accountAsset(s.getAccountAsset())
                    .symbol(s.getSymbol())
                    .timeframe(s.getTimeframe())
                    .cachedCandlesLimit(s.getCachedCandlesLimit())

                    .maxExposureUsd(s.getMaxExposureUsd())
                    .maxExposurePct(s.getMaxExposurePct())
                    .dailyLossLimitPct(s.getDailyLossLimitPct())

                    .reinvestProfit(s.isReinvestProfit())

                    .mlConfidence(s.getMlConfidence())
                    .totalProfitPct(s.getTotalProfitPct())
                    .build();
        }
    }

    // =========================================================
    // helpers (prod-safe, без хардкодов)
    // =========================================================

    private static ResponseEntity<?> badRequest(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "ok", false,
                "error", "BAD_REQUEST",
                "message", msg
        ));
    }

    private static void requireNonNull(Object v, String msg) {
        if (v == null) throw new IllegalArgumentException(msg);
    }

    private static String nowHHmmss() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private static String normalizeScope(String scope) {
        if (scope == null) return "";
        String s = scope.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "";
        if (!s.equals("general") && !s.equals("risk") && !s.equals("trade")) return "";
        return s;
    }

    /** ✅ Без дефолта BINANCE — если пусто, это ошибка клиента. */
    private static String normalizeExchangeOrThrow(String exchange) {
        if (exchange == null) throw new IllegalArgumentException("exchange is required");
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        if (ex.isEmpty()) throw new IllegalArgumentException("exchange is required");
        return ex;
    }

    private static String normalizeAssetOrNull(String asset) {
        if (asset == null) return null;
        String a = asset.trim().toUpperCase(Locale.ROOT);
        return a.isEmpty() ? null : a;
    }

    private static String normalizeSymbolOrNull(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normalizeTimeframeOrNull(String timeframe) {
        if (timeframe == null) return null;
        String s = timeframe.trim();
        if (s.isEmpty()) return null;
        return s.toLowerCase(Locale.ROOT);
    }

    private static BigDecimal parseBdOrNull(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace(",", ".");
        if (s.isEmpty()) return null;
        try { return new BigDecimal(s); }
        catch (Exception e) { return null; }
    }

    private static Integer parseIntOrNull(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try { return Integer.parseInt(s); }
        catch (Exception e) { return null; }
    }

    private static BigDecimal validatePctOrNull(BigDecimal v) {
        if (v == null) return null;
        if (v.compareTo(BigDecimal.ZERO) < 0) return null;
        return v.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal validateMoneyOrNull(BigDecimal v) {
        if (v == null) return null;
        if (v.compareTo(BigDecimal.ZERO) < 0) return null;
        return v.setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal validatePositiveOrNull(BigDecimal v) {
        if (v == null) return null;
        if (v.compareTo(BigDecimal.ZERO) <= 0) return null;
        return v;
    }

    private static Integer toNullablePositive(Integer v) {
        if (v == null) return null;
        return v > 0 ? v : null;
    }

    private static <E extends Enum<E>> E parseEnumOrNull(Class<E> enumClass, String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try { return Enum.valueOf(enumClass, s.toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return null; }
    }
}
