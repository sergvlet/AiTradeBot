package com.chicu.aitradebot.ai.ml.dataset;

import com.chicu.aitradebot.ai.ml.features.MlFeatureBuilder;
import com.chicu.aitradebot.ai.ml.features.MlFeatureContext;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.signal.SignalType;
import com.chicu.aitradebot.trade.TradeClosedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlDatasetCollector {

    private final MlSampleRepository repo;
    private final MlFeatureBuilder featureBuilder;
    private final ObjectMapper objectMapper;

    private final AtomicLong savedCount = new AtomicLong(0);
    private final AtomicLong lastInfoLogMs = new AtomicLong(0);

    @EventListener
    public void onTradeClosed(TradeClosedEvent e) {
        if (e == null) return;

        Long chatId = null;
        StrategyType type = null;
        String symbol = null;
        String tf = null;

        try {
            chatId = e.chatId();
            type = e.strategyType();
            symbol = safeSym(e.symbol());
            tf = safeTf(e.timeframe());

            if (chatId == null || chatId <= 0) return;
            if (type == null) return;
            if (symbol == null || symbol.isBlank()) return;

            // ✅ label пока простой (потом заменим на TP/SL labeler по H свечам)
            String label = (e.pnlPct() != null && e.pnlPct().signum() >= 0) ? "1" : "0";

            // ✅ ts: лучше брать время сделки/закрытия если есть в event
            Instant ts = extractInstant(e,
                    "ts", "timestamp", "tradeTs", "closeTs", "closedAt", "executedAt",
                    "getTs", "getTimestamp", "getTradeTs", "getCloseTs", "getClosedAt", "getExecutedAt"
            );
            if (ts == null) ts = Instant.now();

            // ✅ exchange/network должны приходить из TradeClosedEvent
            String exchange = extractString(e,
                    "exchange", "exchangeName", "ex", "exchangeId",
                    "getExchange", "getExchangeName", "getEx", "getExchangeId"
            );
            exchange = normUpper(exchange);

            NetworkType network = extractEnum(e, NetworkType.class,
                    "network", "networkType", "net", "netType",
                    "getNetwork", "getNetworkType", "getNet", "getNetType"
            );

            // =====================================================
            // extra/meta: собираем полезные поля по сделке
            // =====================================================

            Map<String, Object> extra = new HashMap<>();
            if (e.pnlPct() != null) extra.put("pnlPct", e.pnlPct());
            if (e.exitReason() != null) extra.put("exitReason", e.exitReason());

            // попробуем вытащить доп. поля, если есть
            Object entryPrice = readAny(e, "entryPrice", "getEntryPrice");
            Object exitPrice = readAny(e, "exitPrice", "getExitPrice", "closePrice", "getClosePrice");
            Object qty = readAny(e, "qty", "quantity", "getQty", "getQuantity");

            Object pnlUsd = readAny(e, "pnlUsd", "pnl", "profitUsd", "getPnlUsd", "getPnl", "getProfitUsd");

            if (entryPrice != null) extra.put("entryPrice", entryPrice);
            if (exitPrice != null) extra.put("exitPrice", exitPrice);
            if (qty != null) extra.put("qty", qty);
            if (pnlUsd != null) extra.put("pnlUsd", pnlUsd);

            // tp/sl hit (если есть флаги — берём их; иначе пытаемся вывести из exitReason)
            Boolean tpHit = extractBool(e,
                    "tpHit", "takeProfitHit", "isTakeProfitHit", "getTakeProfitHit", "isTpHit", "getTpHit"
            );
            Boolean slHit = extractBool(e,
                    "slHit", "stopLossHit", "isStopLossHit", "getStopLossHit", "isSlHit", "getSlHit"
            );

            String exitReasonStr = (e.exitReason() != null) ? String.valueOf(e.exitReason()) : null;
            if (tpHit == null || slHit == null) {
                InferHit inf = inferTpSlFromReason(exitReasonStr);
                if (tpHit == null) tpHit = inf.tpHit;
                if (slHit == null) slHit = inf.slHit;
            }

            if (tpHit != null) extra.put("tpHit", tpHit);
            if (slHit != null) extra.put("slHit", slHit);

            // =====================================================
            // Контекст для featureBuilder
            // =====================================================

            MlFeatureContext ctx = MlFeatureContext.builder()
                    .chatId(chatId)
                    .strategyType(type)
                    .symbol(symbol)
                    .timeframe(tf)
                    .action(SignalType.BUY.name()) // обучаем "вход" -> win/lose
                    .extra(extra)
                    .build();

            Map<String, Object> rawFeatures = featureBuilder.build(ctx);
            if (rawFeatures == null || rawFeatures.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("🧠 ML dataset: пустые фичи, пропуск. chatId={} type={} sym={}",
                            chatId, type, symbol);
                }
                return;
            }

            // ✅ FeatureSpec: сохраняем только фичи стратегии и в стабильном порядке
            List<String> spec = resolveFeatureSpec(featureBuilder, ctx, type);
            ObjectNode featuresNode = objectMapper.createObjectNode();

            if (spec != null && !spec.isEmpty()) {
                for (String k : spec) {
                    Object v = rawFeatures.get(k);
                    putJsonValue(featuresNode, k, v);
                }
            } else {
                // fallback: стабильная сортировка ключей (чтобы не ломать обучение)
                TreeMap<String, Object> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                sorted.putAll(rawFeatures);
                for (Map.Entry<String, Object> en : sorted.entrySet()) {
                    putJsonValue(featuresNode, en.getKey(), en.getValue());
                }
            }

            JsonNode featuresJson = featuresNode;

            // =====================================================
            // metaJson (обязательные поля)
            // =====================================================

            ObjectNode meta = objectMapper.createObjectNode();

            meta.put("event", "TRADE_CLOSED");
            meta.put("trainAction", SignalType.BUY.name());
            meta.put("label", label);

            meta.put("strategy", type.name());
            meta.put("chatId", chatId);
            meta.put("symbol", symbol);
            meta.put("timeframe", tf);
            meta.put("ts", ts.toEpochMilli());

            if (exchange != null) meta.put("exchange", exchange);
            if (network != null) meta.put("network", network.name());

            if (e.pnlPct() != null) meta.put("pnlPct", e.pnlPct().doubleValue());
            if (pnlUsd != null) putMetaAny(meta, "pnlUsd", pnlUsd);

            if (exitReasonStr != null && !exitReasonStr.isBlank()) meta.put("exitReason", exitReasonStr);

            // ✅ новые поля по чек-листу
            if (entryPrice != null) putMetaAny(meta, "entryPrice", entryPrice);
            if (exitPrice != null) putMetaAny(meta, "exitPrice", exitPrice);
            if (qty != null) putMetaAny(meta, "qty", qty);

            if (tpHit != null) meta.put("tpHit", tpHit);
            if (slHit != null) meta.put("slHit", slHit);

            if (spec != null && !spec.isEmpty()) {
                ArrayNode a = meta.putArray("featureSpec");
                for (String k : spec) a.add(k);
            }

            meta.put("featureCount", featuresNode.size());
            meta.put("schemaHash", sha256Hex(featuresNode.toString()));

            MlSampleEntity sample = MlSampleEntity.builder()
                    .chatId(chatId)
                    .strategyType(type)
                    .exchange(exchange)
                    .network(network != null ? network.name() : null)
                    .symbol(symbol)
                    .timeframe(tf)
                    .ts(ts)
                    .label(label)
                    .target("win")
                    .proba(null)
                    .featuresJson(featuresJson)
                    .metaJson(meta)
                    .createdAt(Instant.now())
                    .build();

            repo.save(sample);

            long c = savedCount.incrementAndGet();
            maybeInfoLog(chatId, type, exchange, network, symbol, tf, c);

        } catch (Exception ex) {
            log.warn("🧠 ML sample save failed: chatId={} type={} sym={} tf={} err={}",
                    chatId, type, symbol, tf, ex.toString());
        }
    }

    private void maybeInfoLog(Long chatId,
                              StrategyType type,
                              String exchange,
                              NetworkType network,
                              String symbol,
                              String tf,
                              long saved) {

        long now = System.currentTimeMillis();
        long last = lastInfoLogMs.get();

        boolean timeOk = (now - last) >= 60_000L; // раз в минуту
        boolean countOk = (saved % 50L) == 0L;    // или каждые 50 записей

        if (timeOk || countOk) {
            if (lastInfoLogMs.compareAndSet(last, now)) {
                log.info("🧠 ML sample saved: count={} chatId={} type={} ex={} net={} sym={} tf={}",
                        saved, chatId, type,
                        exchange, (network != null ? network.name() : null),
                        symbol, tf
                );
            }
        }
    }

    // =====================================================
    // FeatureSpec resolve (reflection-safe)
    // =====================================================

    @SuppressWarnings("unchecked")
    private List<String> resolveFeatureSpec(MlFeatureBuilder builder, MlFeatureContext ctx, StrategyType type) {
        if (builder == null) return null;

        Object v;

        v = tryInvoke(builder, "featureSpec", new Class<?>[]{MlFeatureContext.class}, ctx);
        if (v == null) v = tryInvoke(builder, "getFeatureSpec", new Class<?>[]{MlFeatureContext.class}, ctx);

        if (v == null) v = tryInvoke(builder, "featureSpec", new Class<?>[]{StrategyType.class}, type);
        if (v == null) v = tryInvoke(builder, "getFeatureSpec", new Class<?>[]{StrategyType.class}, type);

        if (v == null) v = tryInvoke(builder, "featureSpec", new Class<?>[]{}, new Object[]{});
        if (v == null) v = tryInvoke(builder, "getFeatureSpec", new Class<?>[]{}, new Object[]{});

        if (v == null) return null;

        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out.isEmpty() ? null : out;
        }

        if (v instanceof String[] arr) {
            List<String> out = new ArrayList<>();
            for (String s : arr) {
                if (s == null) continue;
                String x = s.trim();
                if (!x.isEmpty()) out.add(x);
            }
            return out.isEmpty() ? null : out;
        }

        if (v instanceof Set<?> set) {
            List<String> out = new ArrayList<>();
            for (Object o : set) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
            out.sort(String.CASE_INSENSITIVE_ORDER);
            return out.isEmpty() ? null : out;
        }

        return null;
    }

    private Object tryInvoke(Object target, String method, Class<?>[] types, Object... args) {
        try {
            Method m = target.getClass().getMethod(method, types);
            return m.invoke(target, args);
        } catch (Exception ignored) {
            return null;
        }
    }

    // =====================================================
    // JSON helpers
    // =====================================================

    private void putJsonValue(ObjectNode node, String key, Object v) {
        if (node == null || key == null) return;

        if (v == null) {
            node.putNull(key);
            return;
        }

        try {
            if (v instanceof Boolean b) { node.put(key, b); return; }
            if (v instanceof Integer i) { node.put(key, i); return; }
            if (v instanceof Long l) { node.put(key, l); return; }
            if (v instanceof Double d) { node.put(key, d); return; }
            if (v instanceof Float f) { node.put(key, f.doubleValue()); return; }
            if (v instanceof Number n) { node.put(key, n.doubleValue()); return; }
            if (v instanceof String s) { node.put(key, s); return; }

            node.set(key, objectMapper.valueToTree(v));

        } catch (Exception ex) {
            node.put(key, String.valueOf(v));
        }
    }

    private void putMetaAny(ObjectNode meta, String key, Object v) {
        if (meta == null || key == null || v == null) return;

        try {
            if (v instanceof Boolean b) { meta.put(key, b); return; }
            if (v instanceof Integer i) { meta.put(key, i); return; }
            if (v instanceof Long l) { meta.put(key, l); return; }
            if (v instanceof Double d) { meta.put(key, d); return; }
            if (v instanceof Float f) { meta.put(key, f.doubleValue()); return; }
            if (v instanceof Number n) { meta.put(key, n.doubleValue()); return; }
            if (v instanceof String s) { meta.put(key, s); return; }

            meta.set(key, objectMapper.valueToTree(v));
        } catch (Exception ex) {
            meta.put(key, String.valueOf(v));
        }
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "sha256_error";
        }
    }

    // =====================================================
    // Reflection helpers (event fields)
    // =====================================================

    private static Object readAny(Object target, String... names) {
        if (target == null || names == null) return null;

        for (String n : names) {
            if (n == null || n.isBlank()) continue;

            // method
            try {
                Method m = target.getClass().getMethod(n);
                if (m.getParameterCount() == 0) return m.invoke(target);
            } catch (Exception ignored) {}

            // field
            try {
                Field f = target.getClass().getDeclaredField(n);
                f.setAccessible(true);
                return f.get(target);
            } catch (Exception ignored) {}
        }

        return null;
    }

    private static String extractString(Object target, String... names) {
        Object v = readAny(target, names);
        if (v == null) return null;
        if (v instanceof Enum<?> e) return e.name();
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Instant extractInstant(Object target, String... names) {
        Object v = readAny(target, names);
        if (v == null) return null;

        if (v instanceof Instant i) return i;
        if (v instanceof java.time.LocalDateTime ldt) {
            return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        if (v instanceof Number n) {
            long ms = n.longValue();
            if (ms > 0) return Instant.ofEpochMilli(ms);
        }
        try {
            long ms = Long.parseLong(String.valueOf(v).trim());
            if (ms > 0) return Instant.ofEpochMilli(ms);
        } catch (Exception ignored) {}

        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Enum<E>> E extractEnum(Object target, Class<E> enumType, String... names) {
        Object v = readAny(target, names);
        if (v == null) return null;

        if (enumType.isInstance(v)) return (E) v;

        if (v instanceof String s) {
            String x = s.trim().toUpperCase(Locale.ROOT);
            if (x.isEmpty()) return null;
            try { return Enum.valueOf(enumType, x); } catch (Exception ignored) {}
        }

        if (v instanceof Enum<?> e) {
            String x = e.name();
            try { return Enum.valueOf(enumType, x); } catch (Exception ignored) {}
        }

        return null;
    }

    private static Boolean extractBool(Object target, String... names) {
        Object v = readAny(target, names);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("n")) return false;
        return null;
    }

    // =====================================================
    // Small utils
    // =====================================================

    private static String safeSym(String s) {
        if (s == null) return null;
        String x = s.trim().toUpperCase(Locale.ROOT);
        return x.isEmpty() ? null : x;
    }

    private static String safeTf(String s) {
        if (s == null) return "1m";
        String x = s.trim();
        return x.isEmpty() ? "1m" : x;
    }

    private static String normUpper(String s) {
        if (s == null) return null;
        String x = s.trim().toUpperCase(Locale.ROOT);
        return x.isEmpty() ? null : x;
    }

    private record InferHit(Boolean tpHit, Boolean slHit) {}

    private static InferHit inferTpSlFromReason(String reason) {
        if (reason == null) return new InferHit(null, null);

        String r = reason.trim().toUpperCase(Locale.ROOT);
        if (r.isEmpty()) return new InferHit(null, null);

        Boolean tp = null;
        Boolean sl = null;

        if (r.contains("TAKE_PROFIT") || r.equals("TP") || r.contains(" TP") || r.contains("TP_") || r.contains("PROFIT")) {
            tp = true;
        }
        if (r.contains("STOP_LOSS") || r.equals("SL") || r.contains(" SL") || r.contains("SL_") || r.contains("LOSS")) {
            sl = true;
        }

        return new InferHit(tp, sl);
    }
}