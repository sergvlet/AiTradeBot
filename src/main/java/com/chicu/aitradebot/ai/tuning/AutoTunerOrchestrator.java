package com.chicu.aitradebot.ai.tuning;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class AutoTunerOrchestrator {

    private final Map<StrategyType, StrategyAutoTuner> tuners = new EnumMap<>(StrategyType.class);

    /**
     * ✅ Защита от дублей: один контекст (chatId/type/exchange/network) — один тюнинг одновременно.
     */
    private final Set<TuningKey> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * ✅ Debounce/anti-spam:
     * чтобы при автосейве/перерендерах не запускать тюнер много раз на один и тот же контекст.
     */
    private final Map<TuningKey, LastRun> lastRunByKey = new ConcurrentHashMap<>();

    /**
     * Настройки дебаунса:
     * - если сигнатура контекста та же самая и прошло меньше cooldownMs — пропускаем.
     */
    private final long cooldownMs = 60_000L; // 60 секунд

    public AutoTunerOrchestrator(List<StrategyAutoTuner> tunerList) {
        for (StrategyAutoTuner t : tunerList) {
            StrategyType type = t.getStrategyType();
            if (type == null) continue;

            StrategyAutoTuner prev = tuners.put(type, t);
            if (prev != null) {
                log.warn("⚠️ Найдено 2 тюнера для {}: {} и {}. Использую последний.",
                        type, prev.getClass().getSimpleName(), t.getClass().getSimpleName());
            }
        }

        log.info("🧠 AI AutoTunerOrchestrator поднят. Тюнеров зарегистрировано: {}", tuners.size());
    }

    public TuningResult tune(TuningRequest request) {

        // ==========================
        // ✅ Валидация + нормализация
        // ==========================
        if (request == null) {
            return reject("request = null");
        }
        if (request.chatId() == null || request.chatId() <= 0) {
            return reject("chatId не задан");
        }
        if (request.strategyType() == null) {
            return reject("strategyType не задан");
        }

        StrategyType type = request.strategyType();
        String exchange = normalizeExchange(request.exchange());

        // ✅ network может быть null из UI — не режем, берём дефолт
        NetworkType network = (request.network() != null ? request.network() : NetworkType.MAINNET);

        StrategyAutoTuner tuner = tuners.get(type);
        if (tuner == null) {
            return reject("Тюнер для " + type + " не зарегистрирован");
        }

        // ==========================
        // ✅ Анти-гонка (один ctx -> один тюнинг)
        // ==========================
        TuningKey key = new TuningKey(request.chatId(), type, exchange, network);

        if (!inFlight.add(key)) {
            return TuningResult.builder()
                    .applied(false)
                    .reason("Тюнинг уже выполняется для этого контекста")
                    .build();
        }

        long started = System.currentTimeMillis();

        try {
            // ✅ Нормализованный запрос, который реально уйдёт в тюнер
            TuningRequest normalized = TuningRequest.builder()
                    .chatId(request.chatId())
                    .strategyType(type)
                    .exchange(exchange)
                    .network(network)
                    .symbol(request.symbol())
                    .timeframe(request.timeframe())
                    .candlesLimit(request.candlesLimit())
                    .startAt(request.startAt())
                    .endAt(request.endAt())
                    .seed(request.seed())
                    .reason(request.reason())
                    .build();

            // ==========================
            // ✅ Debounce по "сигнатуре"
            // ==========================
            String signature = signatureOf(normalized);

            LastRun last = lastRunByKey.get(key);
            long now = System.currentTimeMillis();
            if (last != null && signature.equals(last.signature()) && (now - last.atMs()) < cooldownMs) {
                log.info("🧠 TUNE SKIP (cooldown) chatId={} type={} ex={} net={} signature={} ageMs={}",
                        request.chatId(), type, exchange, network, signature, (now - last.atMs()));

                return TuningResult.builder()
                        .applied(false)
                        .reason("Тюнинг пропущен (cooldown на тот же контекст)")
                        .modelVersion(last.modelVersion())
                        .build();
            }

            log.info("🧠 TUNE START chatId={} type={} ex={} net={} sym={} tf={} candles={} reason={}",
                    normalized.chatId(),
                    type,
                    exchange,
                    network,
                    safe(normalized.symbol()),
                    safe(normalized.timeframe()),
                    normalized.candlesLimit(),
                    safe(normalized.reason())
            );

            // ставим lastRun заранее, чтобы при шквале одинаковых автосейвов не стартануло параллельно
            lastRunByKey.put(key, new LastRun(signature, now, null));

            TuningResult res = tuner.tune(normalized);
            if (res == null) {
                return TuningResult.builder()
                        .applied(false)
                        .reason("Тюнер вернул null")
                        .build();
            }

            long took = System.currentTimeMillis() - started;

            log.info("✅ TUNE DONE applied={} scoreBefore={} scoreAfter={} model={} tookMs={} reason={}",
                    res.applied(),
                    res.scoreBefore(),
                    res.scoreAfter(),
                    safe(res.modelVersion()),
                    took,
                    safe(res.reason())
            );

            // ✅ фиксируем modelVersion для skip-сообщений
            lastRunByKey.put(key, new LastRun(signature, System.currentTimeMillis(), safe(res.modelVersion())));

            return res;

        } catch (Exception e) {
            long took = System.currentTimeMillis() - started;
            log.error("❌ TUNE FAILED tookMs={} chatId={} type={} ex={} net={} : {}",
                    took, request.chatId(), type, exchange, network, e.getMessage(), e);

            return TuningResult.builder()
                    .applied(false)
                    .reason("Ошибка тюнинга: " + safe(e.getMessage()))
                    .build();

        } finally {
            inFlight.remove(key);
        }
    }

    // =========================================================
    // helpers
    // =========================================================

    private static TuningResult reject(String reason) {
        return TuningResult.builder()
                .applied(false)
                .reason(reason)
                .build();
    }

    private static String normalizeExchange(String exchange) {
        if (exchange == null) return "BINANCE";
        String ex = exchange.trim().toUpperCase(Locale.ROOT);
        return ex.isEmpty() ? "BINANCE" : ex;
    }

    private static String safe(String s) {
        if (s == null) return "";
        String x = s.trim();
        return x.length() > 200 ? x.substring(0, 200) : x;
    }

    private static String signatureOf(TuningRequest r) {
        // Важно: сюда входят поля, которые должны ТРИГГЕРИТЬ новый тюнинг
        // (symbol/timeframe/candlesLimit/exchange/network + strategyType).
        String ex = normalizeExchange(r.exchange());
        NetworkType net = (r.network() != null ? r.network() : NetworkType.MAINNET);

        String sym = (r.symbol() == null ? "" : r.symbol().trim().toUpperCase(Locale.ROOT));
        String tf = (r.timeframe() == null ? "" : r.timeframe().trim().toLowerCase(Locale.ROOT));

        int cl = (r.candlesLimit() == null ? 0 : r.candlesLimit());

        return r.strategyType() + "|" + ex + "|" + net + "|" + sym + "|" + tf + "|" + cl;
    }

    private record TuningKey(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    ) {}

    private record LastRun(
            String signature,
            long atMs,
            String modelVersion
    ) {}
}
