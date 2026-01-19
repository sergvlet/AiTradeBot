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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/strategy/settings")
public class StrategySettingsApiController {

    private final StrategySettingsService strategySettingsService;
    private final AccountBalanceService accountBalanceService;
    private final AutoTunerOrchestrator autoTunerOrchestrator;

    // =========================================================
    // 1) POST /asset — смена выбранного актива + возврат snapshot
    // =========================================================
    @PostMapping("/asset")
    public AccountBalanceSnapshot changeAsset(
            @RequestParam long chatId,
            @RequestParam StrategyType type,
            @RequestParam String exchange,
            @RequestParam NetworkType network,
            @RequestParam String asset
    ) {
        String ex = normalizeExchange(exchange);

        StrategySettings s = strategySettingsService.getOrCreate(chatId, type, ex, network);

        String normalized = normalizeAssetOrNull(asset);
        s.setAccountAsset(normalized); // null = очистить выбор
        strategySettingsService.save(s);

        return accountBalanceService.getSnapshot(chatId, type, ex, network);
    }

    // =========================================================
    // 2) GET /balance — вернуть актуальный snapshot
    // =========================================================
    @GetMapping("/balance")
    public AccountBalanceSnapshot getBalance(
            @RequestParam long chatId,
            @RequestParam StrategyType type,
            @RequestParam String exchange,
            @RequestParam NetworkType network,
            @RequestParam(required = false) String asset
    ) {
        String ex = normalizeExchange(exchange);

        // если параметр asset присутствует (даже пустой) — сохраняем (включая очистку -> null)
        if (asset != null) {
            StrategySettings s = strategySettingsService.getOrCreate(chatId, type, ex, network);
            s.setAccountAsset(normalizeAssetOrNull(asset));
            strategySettingsService.save(s);
        }

        return accountBalanceService.getSnapshot(chatId, type, ex, network);
    }

    // =========================================================
    // 3) POST /autosave — автосейв StrategySettings + snapshot
    // scope: "general" | "risk" | "trade" | "" (пусто = применить всё)
    // =========================================================
    @PostMapping("/autosave")
    public ResponseEntity<AutosaveResponse> autosave(@RequestBody AutosaveRequest req) {

        String ex = normalizeExchange(req.getExchange());

        StrategySettings s = strategySettingsService.getOrCreate(
                req.getChatId(),
                req.getType(),
                ex,
                req.getNetwork()
        );

        // =========================================================
        // ✅ РЕЖИМ — сохраняем ВСЕГДА, если пришёл валидный (не зависит от scope)
        // =========================================================
        AdvancedControlMode before = s.getAdvancedControlMode();
        AdvancedControlMode parsed = parseEnumOrNull(AdvancedControlMode.class, req.getAdvancedControlMode());

        if (req.getAdvancedControlMode() != null) {
            // пришло с фронта (даже если пусто) — логируем
            log.info("🧠 AUTOSAVE MODE incoming='{}' parsed={} before={} ctx: chatId={} type={} ex={} net={}",
                    req.getAdvancedControlMode(), parsed, before,
                    req.getChatId(), req.getType(), ex, req.getNetwork());
        }

        if (parsed != null && parsed != before) {
            s.setAdvancedControlMode(parsed);
        }

        String scope = normalizeScope(req.getScope());
        boolean applyAll     = scope.isEmpty();
        boolean applyGeneral = applyAll || scope.equals("general");
        boolean applyRisk    = applyAll || scope.equals("risk");
        boolean applyTrade   = applyAll || scope.equals("trade");

        // -------------------------
        // GENERAL
        // -------------------------
        if (applyGeneral) {

            if (req.getAccountAsset() != null) {
                // можно и очистить (пустая строка -> null)
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

            String sym = normalizeSymbolOrNull(req.getSymbol());
            if (sym != null) s.setSymbol(sym);

            String tf = normalizeTimeframeOrNull(req.getTimeframe());
            if (tf != null) s.setTimeframe(tf);

            Integer candles = parseIntOrNull(req.getCachedCandlesLimit());
            if (candles != null) {
                if (candles < 50) candles = 50;
                s.setCachedCandlesLimit(candles);
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

            Integer lev = parseIntOrNull(req.getLeverage());
            if (lev != null) {
                if (lev < 1) lev = 1;
                s.setLeverage(lev);
            }

            if (req.getAllowAveraging() != null) {
                s.setAllowAveraging(req.getAllowAveraging());
            }

            if (req.getCooldownSeconds() != null) {
                Integer cd = parseIntOrNull(req.getCooldownSeconds());
                s.setCooldownSeconds(toNullablePositive(cd));
            }

            if (req.getMaxTradesPerDay() != null) {
                Integer mtd = parseIntOrNull(req.getMaxTradesPerDay());
                s.setMaxTradesPerDay(toNullablePositive(mtd));
            }

            if (req.getMaxOpenOrders() != null) {
                Integer moo = parseIntOrNull(req.getMaxOpenOrders());
                s.setMaxOpenOrders(toNullablePositive(moo));
            }

            // drawdown pct/usd взаимоисключающие
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

            // position pct/usd взаимоисключающие
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
                Integer v = parseIntOrNull(req.getCooldownAfterLossSeconds());
                s.setCooldownAfterLossSeconds(toNullablePositive(v));
            }
            if (req.getMaxConsecutiveLosses() != null) {
                Integer v = parseIntOrNull(req.getMaxConsecutiveLosses());
                s.setMaxConsecutiveLosses(toNullablePositive(v));
            }
        }

        // ✅ сохраняем ровно одну строку по ключу (это гарантирует getOrCreate + UNIQUE)
        StrategySettings saved = strategySettingsService.save(s);

        AdvancedControlMode after = saved.getAdvancedControlMode();
        if (parsed != null) {
            log.info("✅ AUTOSAVE MODE stored={} (before={}) id={} ctx: chatId={} type={} ex={} net={}",
                    after, before, saved.getId(), req.getChatId(), req.getType(), ex, req.getNetwork());
        }

        AccountBalanceSnapshot snap = accountBalanceService.getSnapshot(
                req.getChatId(),
                req.getType(),
                ex,
                req.getNetwork()
        );

        return ResponseEntity.ok(
                AutosaveResponse.builder()
                        .ok(true)
                        .savedAt(nowHHmmss())
                        .snapshot(snap)

                        // дополнительные поля (UI может синхронизировать вкладки)
                        .advancedControlMode(after != null ? after.name() : null)
                        .accountAsset(saved.getAccountAsset())
                        .symbol(saved.getSymbol())
                        .timeframe(saved.getTimeframe())
                        .cachedCandlesLimit(saved.getCachedCandlesLimit())

                        // ✅ очень полезно для отладки: в какую строку реально сохранили
                        .id(saved.getId())
                        .chatId(saved.getChatId())
                        .type(saved.getType() != null ? saved.getType().name() : null)
                        .exchange(saved.getExchangeName())
                        .network(saved.getNetworkType() != null ? saved.getNetworkType().name() : null)

                        .build()
        );
    }

    // =========================================================
    // 4) POST /apply — применить HYBRID/AI (тюнинг)
    // =========================================================
    @PostMapping("/apply")
    public ResponseEntity<ApplyResponse> apply(@RequestBody ApplyRequest req) {
        String ex = normalizeExchange(req.getExchange());

        StrategySettings s = strategySettingsService.getOrCreate(
                req.getChatId(),
                req.getType(),
                ex,
                req.getNetwork()
        );

        AdvancedControlMode incoming = parseEnumOrNull(AdvancedControlMode.class, req.getAdvancedControlMode());
        if (incoming != null && incoming != s.getAdvancedControlMode()) {
            log.info("🧠 APPLY MODE change {} -> {} ctx: chatId={} type={} ex={} net={}",
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

        return ResponseEntity.ok(
                ApplyResponse.builder()
                        .applied(tr.applied())
                        .reason(tr.reason())
                        .modelVersion(tr.modelVersion())
                        .scoreBefore(tr.scoreBefore())
                        .scoreAfter(tr.scoreAfter())
                        .oldParams(tr.oldParams())
                        .newParams(tr.newParams())
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

        private String advancedControlMode;
        private String accountAsset;
        private String symbol;
        private String timeframe;
        private Integer cachedCandlesLimit;

        // ✅ ДОБАВИЛ: отладочный контекст (не ломает старый UI)
        private Long id;
        private Long chatId;
        private String type;
        private String exchange;
        private String network;
    }

    // =========================================================
    // helpers
    // =========================================================

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

    private static String normalizeExchange(String exchange) {
        if (exchange == null) return "BINANCE";
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        return ex.isEmpty() ? "BINANCE" : ex;
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

    private static <E extends Enum<E>> E parseEnumOrNull(Class<E> enumClass, String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        try { return Enum.valueOf(enumClass, s); }
        catch (Exception e) { return null; }
    }

    private static Integer toNullablePositive(Integer v) {
        if (v == null) return null;
        return v > 0 ? v : null;
    }

    private static BigDecimal validateMoneyOrNull(BigDecimal v) {
        if (v == null) return null;
        if (v.compareTo(BigDecimal.ZERO) <= 0) return null;
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal validatePositiveOrNull(BigDecimal v) {
        if (v == null) return null;
        if (v.compareTo(BigDecimal.ZERO) <= 0) return null;
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal validatePctOrNull(BigDecimal v) {
        if (v == null) return null;
        if (v.compareTo(BigDecimal.ZERO) < 0) v = BigDecimal.ZERO;
        if (v.compareTo(BigDecimal.valueOf(100)) > 0) v = BigDecimal.valueOf(100);
        return v.setScale(4, RoundingMode.HALF_UP);
    }
}
