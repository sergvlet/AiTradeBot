package com.chicu.aitradebot.ai.tuning;

import com.chicu.aitradebot.ai.tuning.eval.StrategyEnvResolver;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class AutoTunerOrchestrator {

    private final Map<StrategyType, StrategyAutoTuner> tuners = new EnumMap<>(StrategyType.class);
    private final StrategyEnvResolver envResolver;

    /** ✅ 1 контекст (chatId/type/exchange/network) — 1 тюнинг одновременно. */
    private final Set<TuningKey> inFlight = ConcurrentHashMap.newKeySet();

    /** ✅ Debounce/anti-spam по контексту (chatId/type/exchange/network) + signature. */
    private final Map<TuningKey, LastRun> lastRunByKey = new ConcurrentHashMap<>();

    /**
     * cooldown для одинакового signature:
     * ✅ применяем ТОЛЬКО если прошлый результат был APPLIED или NO_IMPROVEMENT
     * ❌ НЕ применяем для ERROR/NO_TRADES (чтобы можно было быстро повторить)
     */
    private static final long COOLDOWN_MS = 60_000L;

    public AutoTunerOrchestrator(List<StrategyAutoTuner> tunerList,
                                 StrategyEnvResolver envResolver) {

        this.envResolver = envResolver;

        if (tunerList != null) {
            for (StrategyAutoTuner t : tunerList) {
                if (t == null) continue;
                StrategyType type = t.getStrategyType();
                if (type == null) continue;

                StrategyAutoTuner prev = tuners.put(type, t);
                if (prev != null) {
                    log.warn("⚠️ Найдено 2 тюнера для {}: {} и {}. Использую последний.",
                            type, prev.getClass().getSimpleName(), t.getClass().getSimpleName());
                }
            }
        }
        log.info("🧠 AI AutoTunerOrchestrator поднят. Тюнеров зарегистрировано: {}", tuners.size());
    }

    public TuningResult tune(TuningRequest request) {

        // ==========================
        // ✅ Валидация
        // ==========================
        if (request == null) return reject("request = null");

        Long chatId = request.chatId();
        if (chatId == null || chatId <= 0) return reject("chatId не задан");

        StrategyType type = request.strategyType();
        if (type == null) return reject("strategyType не задан");

        StrategyAutoTuner tuner = tuners.get(type);
        if (tuner == null) return reject("Тюнер для " + type + " не зарегистрирован");

        // ==========================
        // ✅ Безопасный env (НИКАКИХ дефолтов MAINNET/BINANCE)
        // ==========================
        ResolvedEnv env = resolveEnv(chatId, type, request.exchange(), request.network());
        if (!env.ok) return reject(env.failReason);

        // ==========================
        // ✅ Анти-гонка
        // ==========================
        TuningKey key = new TuningKey(chatId, type, env.exchange, env.network);
        if (!inFlight.add(key)) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("Тюнинг уже выполняется для этого контекста")
                    .build();
        }

        long startedAtMs = System.currentTimeMillis();
        String signatureForCatch = "unknown";

        try {
            // ✅ Нормализованный запрос
            TuningRequest normalized = TuningRequest.builder()
                    .chatId(chatId)
                    .strategyType(type)
                    .exchange(env.exchange)
                    .network(env.network)
                    .symbol(normSymbolOrNull(request.symbol()))
                    .timeframe(normTimeframeOrNull(request.timeframe()))
                    .candlesLimit(request.candlesLimit())
                    .startAt(request.startAt())
                    .endAt(request.endAt())
                    .seed(request.seed())
                    .reason(safe(request.reason()))
                    .build();

            String signature = signatureOf(normalized);
            signatureForCatch = signature;

            // ==========================
            // ✅ Cooldown по signature (строгий, но НЕ блокируем ERROR/NO_TRADES)
            // ==========================
            LastRun last = lastRunByKey.get(key);
            long now = System.currentTimeMillis();

            boolean adaptiveReason = isAdaptiveReason(normalized.reason());
            boolean sameSignature = (last != null && signature.equals(last.signature()));
            boolean withinCooldown = (last != null && (now - last.atMs()) < COOLDOWN_MS);
            boolean cooldownApplies = (last != null && (last.outcome() == TuneOutcome.APPLIED || (last.outcome() == TuneOutcome.NO_IMPROVEMENT && !adaptiveReason)));

            if (sameSignature && withinCooldown && cooldownApplies) {
                log.info("🧠 TUNE SKIP (cooldown) chatId={} type={} ex={} net={} signature={} ageMs={} lastOutcome={}",
                        chatId, type, env.exchange, env.network, signature, (now - last.atMs()), last.outcome());

                return TuningResult.builder()
                        .applied(false)
                        .reason("Тюнинг пропущен (cooldown на тот же контекст)")
                        .modelVersion(last.modelVersion())
                        .build();
            }

            log.info("🧠 TUNE START chatId={} type={} ex={} net={} sym={} tf={} candles={} reason={}",
                    chatId, type, env.exchange, env.network,
                    safe(normalized.symbol()),
                    safe(normalized.timeframe()),
                    normalized.candlesLimit(),
                    safe(normalized.reason())
            );

            // фиксируем RUNNING сразу (анти-спам от автосейва/триггеров)
            lastRunByKey.put(key, new LastRun(signature, now, null, TuneOutcome.RUNNING));

            TuningResult res = tuner.tune(normalized);
            if (res == null) {
                lastRunByKey.put(key, new LastRun(signature, System.currentTimeMillis(), null, TuneOutcome.ERROR));
                return TuningResult.builder().applied(false).reason("Тюнер вернул null").build();
            }

            long tookMs = System.currentTimeMillis() - startedAtMs;

            // ==========================
            // ✅ NO_TRADES: даём тюнеру разжать фильтры,
            // и НЕ блокируем следующий прогон cooldown-ом.
            // ==========================
            if (isNoTrades(res)) {
                // Для стартового/обычного live-запуска coarse-adjust опасен:
                // стратегия уже прошла prepare/validate, а этот прогон лишь диагностический.
                // Разжимать фильтры можно только по адаптивным триггерам.
                if (adaptiveReason) {
                    tryAdjustCoarseFilters(tuner, normalized);
                } else if (log.isDebugEnabled()) {
                    log.debug("🧠 NO_TRADES without adaptive reason -> coarse-adjust skipped chatId={} type={} ex={} net={} reason={}",
                            chatId, type, env.exchange, env.network, safe(normalized.reason()));
                }

                log.info("✅ TUNE DONE outcome=NO_TRADES applied=false scoreBefore={} scoreAfter={} model={} tookMs={} reason={}",
                        res.scoreBefore(),
                        res.scoreAfter(),
                        safe(res.modelVersion()),
                        tookMs,
                        safe(res.reason())
                );

                lastRunByKey.put(key, new LastRun(signature, System.currentTimeMillis(), safe(res.modelVersion()), TuneOutcome.NO_TRADES));
                return res; // ✅ возвращаем оригинальный res
            }

            log.info("✅ TUNE DONE applied={} scoreBefore={} scoreAfter={} model={} tookMs={} reason={}",
                    res.applied(),
                    res.scoreBefore(),
                    res.scoreAfter(),
                    safe(res.modelVersion()),
                    tookMs,
                    safe(res.reason())
            );

            if (!res.applied() && adaptiveReason && shouldForceRelaxAfterNoImprovement(res)) {
                tryAdjustCoarseFilters(tuner, normalized);
            }

            TuneOutcome outcome = res.applied() ? TuneOutcome.APPLIED : TuneOutcome.NO_IMPROVEMENT;
            lastRunByKey.put(key, new LastRun(signature, System.currentTimeMillis(), safe(res.modelVersion()), outcome));

            return res;

        } catch (Exception e) {
            long tookMs = System.currentTimeMillis() - startedAtMs;

            log.error("❌ TUNE FAILED tookMs={} chatId={} type={} ex={} net={} : {}",
                    tookMs, chatId, type, env.exchange, env.network, e.getMessage(), e);

            lastRunByKey.put(key, new LastRun(signatureForCatch, System.currentTimeMillis(), null, TuneOutcome.ERROR));

            return TuningResult.builder()
                    .applied(false)
                    .reason("Ошибка тюнинга: " + safe(e.getMessage()))
                    .build();

        } finally {
            inFlight.remove(key);
        }
    }

    // =========================================================
    // env resolve (NO DEFAULTS)
    // =========================================================

    private record ResolvedEnv(boolean ok, String exchange, NetworkType network, String failReason) {}

    private ResolvedEnv resolveEnv(Long chatId,
                                   StrategyType type,
                                   String exchange,
                                   NetworkType network) {

        String ex = normalizeExchangeOrNull(exchange);
        NetworkType net = network;

        if (ex == null || net == null) {
            try {
                StrategyEnvResolver.Env env = envResolver.resolve(chatId, type);
                if (ex == null) ex = normalizeExchangeOrNull(env.exchangeName());
                if (net == null) net = env.networkType();
            } catch (Exception e) {
                return new ResolvedEnv(false, null, null, "Не удалось определить env: " + safeMsg(e));
            }
        }

        if (ex == null) return new ResolvedEnv(false, null, null, "exchange не задан и не найден в настройках");
        if (net == null) return new ResolvedEnv(false, null, null, "network не задан и не найден в настройках");

        return new ResolvedEnv(true, ex, net, null);
    }

    // =========================================================
    // helpers
    // =========================================================

    private static TuningResult reject(String reason) {
        return TuningResult.builder().applied(false).reason(reason).build();
    }


    private static boolean isAdaptiveReason(String reason) {
        String normalized = safe(reason).toLowerCase(Locale.ROOT);
        return normalized.startsWith("starvation:")
                || normalized.startsWith("regime_shift:")
                || normalized.startsWith("loss_recovery:")
                || normalized.startsWith("profit_expand:");
    }

    private static boolean shouldForceRelaxAfterNoImprovement(TuningResult res) {
        if (res == null || res.applied()) {
            return false;
        }
        String reason = safe(res.reason()).toLowerCase(Locale.ROOT);
        return reason.contains("no_improvement") || reason.contains("no_change_needed") || reason.contains("no_trades");
    }

    private static String normalizeExchangeOrNull(String exchange) {
        if (exchange == null) return null;
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        return ex.isEmpty() ? null : ex;
    }

    private static String normSymbolOrNull(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normTimeframeOrNull(String timeframe) {
        if (timeframe == null) return null;
        String s = timeframe.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String safe(String s) {
        if (s == null) return "";
        String x = s.trim();
        return x.length() > 200 ? x.substring(0, 200) : x;
    }

    private static String signatureOf(TuningRequest r) {
        String sym = (r.symbol() == null ? "" : r.symbol().trim().toUpperCase(Locale.ROOT));
        String tf  = (r.timeframe() == null ? "" : r.timeframe().trim().toLowerCase(Locale.ROOT));
        int cl = (r.candlesLimit() == null ? 0 : r.candlesLimit());

        return r.strategyType() + "|" + r.exchange() + "|" + r.network() + "|" + sym + "|" + tf + "|" + cl;
    }

    private static boolean isNoTrades(TuningResult res) {
        if (res == null) return false;

        String reason = safe(res.reason()).toLowerCase(Locale.ROOT);
        if (reason.contains("no_trades") || reason.contains("no trades") || reason.contains("0 trades") || reason.contains("нет сделок")) {
            return true;
        }

        Integer trades = tryExtractTradesCount(res);
        if (trades != null) return trades <= 0;

        try {
            Double sb = toDouble(res.scoreBefore());
            Double sa = toDouble(res.scoreAfter());
            if (!res.applied() && sb != null && sa != null) {
                return (sb <= -1.0 && sa <= -1.0);
            }
        } catch (Exception ignore) {
            // ignore
        }

        return false;
    }

    private static Integer tryExtractTradesCount(TuningResult res) {
        String[] names = {"tradesCount", "totalTrades", "trades"};
        for (String n : names) {
            try {
                Method m = res.getClass().getMethod(n);
                Object v = m.invoke(res);
                if (v instanceof Number num) return num.intValue();
            } catch (NoSuchMethodException ignore) {
                // ignore
            } catch (Exception ignore) {
                // ignore
            }
        }
        return null;
    }

    private static Double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }

    /**
     * ✅ После NO_TRADES даём тюнеру шанс "разжать" грубые фильтры.
     * Опционально, через reflection:
     * - adjustCoarseFilters(TuningRequest)
     * - onNoTrades(TuningRequest)
     */
    private static void tryAdjustCoarseFilters(StrategyAutoTuner tuner, TuningRequest req) {
        if (tuner == null || req == null) return;

        boolean invoked = invokeOptional(tuner, "adjustCoarseFilters", req);
        if (!invoked) {
            invoked = invokeOptional(tuner, "onNoTrades", req);
        }

        if (invoked) {
            log.warn("🧠 NO_TRADES → coarse filters updated by tuner: type={} ex={} net={} sym={} tf={}",
                    req.strategyType(), req.exchange(), req.network(), safe(req.symbol()), safe(req.timeframe()));
        } else {
            log.warn("🧠 NO_TRADES → tuner {} has no coarse filter handler (adjustCoarseFilters/onNoTrades).",
                    tuner.getClass().getSimpleName());
        }
    }

    private static boolean invokeOptional(Object target, String methodName, TuningRequest arg) {
        try {
            Method m = target.getClass().getMethod(methodName, TuningRequest.class);
            m.setAccessible(true);
            m.invoke(target, arg);
            return true;
        } catch (NoSuchMethodException ignore) {
            return false;
        } catch (Exception e) {
            log.warn("⚠️ Ошибка при вызове {}.{}(): {}", target.getClass().getSimpleName(), methodName, e.getMessage());
            return false;
        }
    }

    private static String safeMsg(Throwable e) {
        if (e == null) return "null";
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        return m;
    }

    private record TuningKey(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {}

    private enum TuneOutcome {
        RUNNING,
        APPLIED,
        NO_IMPROVEMENT,
        NO_TRADES,
        ERROR
    }

    private record LastRun(
            String signature,
            long atMs,
            String modelVersion,
            TuneOutcome outcome
    ) {}
}



