package com.chicu.aitradebot.web.ui;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.web.ui.entity.UiStrategyLayerEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class UiStrategyLayerService {

    private final UiStrategyLayerRepository repository;
    private final ObjectMapper objectMapper;

    // TTL
    private static final Duration TTL = Duration.ofHours(24);

    /**
     * ✅ Striped locks вместо map-локов:
     * - нет утечки памяти по ключам
     * - атомарность delete+insert сохраняем (для одного и того же lockKey гарантированно один stripe)
     */
    private static final int LOCK_STRIPES = 1024;
    private final Object[] lockStripes = new Object[LOCK_STRIPES];

    @PostConstruct
    void initLockStripes() {
        for (int i = 0; i < lockStripes.length; i++) {
            lockStripes[i] = new Object();
        }
    }

    // =====================================================
    // 📊 READ — ВСЕ СЛОИ (история)
    // =====================================================
    @Transactional(readOnly = true)
    public List<UiStrategyLayerEntity> loadForChart(Long chatId,
                                                    StrategyType strategyType,
                                                    String symbol) {

        Long cid = normChatId(chatId);
        String sym = normSymbol(symbol);

        if (cid == null || strategyType == null || sym == null) return List.of();

        try {
            return repository.findAllForChart(cid, strategyType, sym);
        } catch (Exception e) {
            log.warn("⚠ UI loadForChart failed chatId={} strategy={} symbol={} err={}",
                    cid, strategyType, sym, e.getMessage());
            return List.of();
        }
    }

    // =====================================================
    // ✅ READ — ПОСЛЕДНИЕ СНАПШОТЫ
    // =====================================================

    @Transactional(readOnly = true)
    public List<Double> loadLatestLevels(Long chatId,
                                         StrategyType strategyType,
                                         String symbol) {

        return loadLatest(chatId, strategyType, symbol, "LEVELS")
                .flatMap(this::parseLevels)
                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadLatestZone(Long chatId,
                                              StrategyType strategyType,
                                              String symbol) {

        return loadLatest(chatId, strategyType, symbol, "ZONE")
                .flatMap(this::parseMap)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> loadLatestOrders(Long chatId,
                                                      StrategyType strategyType,
                                                      String symbol) {

        return loadLatest(chatId, strategyType, symbol, "ORDERS")
                .flatMap(this::parseOrders)
                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadLatestTpSl(Long chatId,
                                              StrategyType strategyType,
                                              String symbol) {

        return loadLatest(chatId, strategyType, symbol, "TPSL")
                .flatMap(this::parseMap)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadLatestBuySellZones(Long chatId,
                                                      StrategyType strategyType,
                                                      String symbol) {

        return loadLatest(chatId, strategyType, symbol, "BUYSELL_ZONES")
                .flatMap(this::parseMap)
                .orElse(null);
    }

    // =====================================================
    // ✅ CLEAR (нужно для clearLevels/clearTpSl/clearZone)
    // =====================================================

    @Transactional
    public void clearLevels(Long chatId, StrategyType strategyType, String symbol) {
        clearByType(chatId, strategyType, symbol, "LEVELS");
    }

    @Transactional
    public void clearZone(Long chatId, StrategyType strategyType, String symbol) {
        clearByType(chatId, strategyType, symbol, "ZONE");
    }

    @Transactional
    public void clearOrders(Long chatId, StrategyType strategyType, String symbol) {
        clearByType(chatId, strategyType, symbol, "ORDERS");
    }

    @Transactional
    public void clearTpSl(Long chatId, StrategyType strategyType, String symbol) {
        clearByType(chatId, strategyType, symbol, "TPSL");
    }

    @Transactional
    public void clearBuySellZones(Long chatId, StrategyType strategyType, String symbol) {
        clearByType(chatId, strategyType, symbol, "BUYSELL_ZONES");
    }

    private void clearByType(Long chatId, StrategyType strategyType, String symbol, String layerType) {
        Long cid = normChatId(chatId);
        String sym = normSymbol(symbol);
        String lt = normLayerType(layerType);

        if (cid == null || strategyType == null || sym == null || lt == null) return;

        withLock(lockKey(cid, strategyType, sym, lt), () -> {
            repository.deleteByType(cid, strategyType, sym, lt);
            return null;
        });
    }

    // =====================================================
    // 🧠 SAVE — LEVELS
    // =====================================================
    @Transactional
    public void saveLevels(Long chatId,
                           StrategyType strategyType,
                           String symbol,
                           Instant candleTime,
                           List<? extends Number> levels) {

        Long cid = normChatId(chatId);
        String sym = normSymbol(symbol);
        if (cid == null || strategyType == null || sym == null) return;

        final String lt = "LEVELS";

        withLock(lockKey(cid, strategyType, sym, lt), () -> {
            repository.deleteByType(cid, strategyType, sym, lt);

            // ✅ пустой список = очистка слоя
            if (levels == null || levels.isEmpty()) return null;

            saveLayer(cid, strategyType, sym, lt, candleTime, Map.of("levels", levels));
            return null;
        });
    }

    // =====================================================
    // 🟠 SAVE — ZONE
    // =====================================================
    @Transactional
    public void saveZone(Long chatId,
                         StrategyType strategyType,
                         String symbol,
                         Instant candleTime,
                         double top,
                         double bottom,
                         String color) {

        Long cid = normChatId(chatId);
        String sym = normSymbol(symbol);
        if (cid == null || strategyType == null || sym == null) return;

        final String lt = "ZONE";

        withLock(lockKey(cid, strategyType, sym, lt), () -> {
            repository.deleteByType(cid, strategyType, sym, lt);

            // ⚠️ Map.of не принимает null значения
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("top", top);
            payload.put("bottom", bottom);
            if (color != null && !color.isBlank()) payload.put("color", color);

            saveLayer(cid, strategyType, sym, lt, candleTime, payload);
            return null;
        });
    }

    // =====================================================
    // 🟢 SAVE — ORDERS
    // =====================================================
    @Transactional
    public void saveOrders(Long chatId,
                           StrategyType strategyType,
                           String symbol,
                           Instant candleTime,
                           List<Map<String, Object>> orders) {

        Long cid = normChatId(chatId);
        String sym = normSymbol(symbol);
        if (cid == null || strategyType == null || sym == null) return;

        final String lt = "ORDERS";

        withLock(lockKey(cid, strategyType, sym, lt), () -> {
            repository.deleteByType(cid, strategyType, sym, lt);

            // ✅ null/empty = очистка слоя
            if (orders == null || orders.isEmpty()) return null;

            saveLayer(cid, strategyType, sym, lt, candleTime, Map.of("orders", orders));
            return null;
        });
    }

    // =====================================================
    // 🎯 SAVE — TP / SL
    // =====================================================
    @Transactional
    public void saveTpSl(Long chatId,
                         StrategyType strategyType,
                         String symbol,
                         Instant candleTime,
                         Double tp,
                         Double sl) {

        Long cid = normChatId(chatId);
        String sym = normSymbol(symbol);
        if (cid == null || strategyType == null || sym == null) return;

        final String lt = "TPSL";

        withLock(lockKey(cid, strategyType, sym, lt), () -> {
            repository.deleteByType(cid, strategyType, sym, lt);

            // ✅ оба null = очистка слоя
            if (tp == null && sl == null) return null;

            Map<String, Object> payload = new LinkedHashMap<>();
            if (tp != null) payload.put("tp", tp);
            if (sl != null) payload.put("sl", sl);
            payload.put("colorTp", "rgba(34,197,94,0.9)");
            payload.put("colorSl", "rgba(239,68,68,0.9)");

            saveLayer(cid, strategyType, sym, lt, candleTime, payload);
            return null;
        });
    }

    // =====================================================
    // 🔴 SAVE — BUY / SELL ZONES
    // =====================================================
    @Transactional
    public void saveBuySellZones(Long chatId,
                                 StrategyType strategyType,
                                 String symbol,
                                 Instant candleTime,
                                 Map<String, Object> buyZone,
                                 Map<String, Object> sellZone) {

        Long cid = normChatId(chatId);
        String sym = normSymbol(symbol);
        if (cid == null || strategyType == null || sym == null) return;

        final String lt = "BUYSELL_ZONES";

        withLock(lockKey(cid, strategyType, sym, lt), () -> {
            repository.deleteByType(cid, strategyType, sym, lt);

            // ✅ оба null = очистка слоя
            if (buyZone == null && sellZone == null) return null;

            Map<String, Object> payload = new LinkedHashMap<>();
            if (buyZone != null) payload.put("buy", buyZone);
            if (sellZone != null) payload.put("sell", sellZone);

            saveLayer(cid, strategyType, sym, lt, candleTime, payload);
            return null;
        });
    }

    // =====================================================
    // 🔵 INTERNAL SAVE (только INSERT)
    // =====================================================
    private void saveLayer(Long chatId,
                           StrategyType strategyType,
                           String symbol,
                           String layerType,
                           Instant candleTime,
                           Object payloadObj) {

        if (chatId == null || strategyType == null || symbol == null || layerType == null) return;
        if (payloadObj == null) return;

        try {
            String json = objectMapper.writeValueAsString(payloadObj);

            UiStrategyLayerEntity entity = UiStrategyLayerEntity.builder()
                    .chatId(chatId)
                    .strategyType(strategyType)
                    .symbol(symbol)
                    .layerType(layerType)
                    .payload(json)
                    .candleTime(candleTime != null ? candleTime : Instant.now())
                    .createdAt(Instant.now())
                    .build();

            repository.save(entity);

            if (log.isDebugEnabled()) {
                log.debug("💾 UI layer saved type={} chatId={} strategy={} symbol={}",
                        layerType, chatId, strategyType, symbol);
            }

        } catch (JsonProcessingException e) {
            log.error("❌ Failed to serialize UI layer payload type={} chatId={} strategy={} symbol={} payloadClass={}",
                    layerType, chatId, strategyType, symbol,
                    payloadObj.getClass().getSimpleName(), e);
        } catch (Exception e) {
            log.error("❌ Failed to save UI layer type={} chatId={} strategy={} symbol={}",
                    layerType, chatId, strategyType, symbol, e);
        }
    }

    // =====================================================
    // 🔍 INTERNAL LOAD
    // =====================================================
    private Optional<UiStrategyLayerEntity> loadLatest(Long chatId,
                                                       StrategyType strategyType,
                                                       String symbol,
                                                       String layerType) {

        Long cid = normChatId(chatId);
        String sym = normSymbol(symbol);
        String lt = normLayerType(layerType);

        if (cid == null || strategyType == null || sym == null || lt == null) return Optional.empty();

        try {
            return repository.findLatestByType(cid, strategyType, sym, lt)
                    .stream()
                    .findFirst();
        } catch (Exception e) {
            log.warn("⚠ UI layer loadLatest failed chatId={} strategy={} symbol={} type={} err={}",
                    cid, strategyType, sym, lt, e.getMessage());
            return Optional.empty();
        }
    }

    // =====================================================
    // 🧩 JSON PARSERS
    // =====================================================
    private Optional<Map<String, Object>> parseMap(UiStrategyLayerEntity e) {
        if (e == null) return Optional.empty();
        String payload = e.getPayload();
        if (payload == null || payload.isBlank()) return Optional.empty();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(payload, Map.class);
            return Optional.ofNullable(map);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private Optional<List<Double>> parseLevels(UiStrategyLayerEntity e) {
        return parseMap(e).map(m -> {
            Object raw = m.get("levels");
            if (!(raw instanceof List<?> list)) return List.<Double>of();

            List<Double> out = new ArrayList<>();
            for (Object v : list) {
                if (v instanceof Number n) out.add(n.doubleValue());
            }
            return out;
        });
    }

    private Optional<List<Map<String, Object>>> parseOrders(UiStrategyLayerEntity e) {
        return parseMap(e).map(m -> {
            Object raw = m.get("orders");
            if (!(raw instanceof List<?> list)) return List.<Map<String, Object>>of();

            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> mm) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    mm.forEach((k, v) -> one.put(String.valueOf(k), v));
                    out.add(one);
                }
            }
            return out;
        });
    }

    // =====================================================
    // 🧹 CLEANUP
    // =====================================================
    @Transactional
    public int cleanupOld() {
        Instant before = Instant.now().minus(TTL);
        try {
            return repository.deleteOlderThan(before);
        } catch (Exception e) {
            log.warn("⚠ UI cleanupOld failed: {}", e.getMessage());
            return 0;
        }
    }

    @Transactional
    public void clearStrategy(Long chatId,
                              StrategyType strategyType,
                              String symbol) {

        Long cid = normChatId(chatId);
        String sym = normSymbol(symbol);

        if (cid == null || strategyType == null || sym == null) return;

        try {
            repository.deleteForStrategy(cid, strategyType, sym);
        } catch (Exception e) {
            log.warn("⚠ UI clearStrategy failed chatId={} strategy={} symbol={} err={}",
                    cid, strategyType, sym, e.getMessage());
        }
    }

    // =====================================================
    // ✅ NORMALIZATION
    // =====================================================

    private static Long normChatId(Long chatId) {
        if (chatId == null || chatId <= 0) return null;
        return chatId;
    }

    private static String normSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normLayerType(String layerType) {
        if (layerType == null) return null;
        String t = layerType.trim().toUpperCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static String lockKey(Long chatId, StrategyType type, String symbol, String layerType) {
        return chatId + "|" + type.name() + "|" + symbol + "|" + layerType;
    }

    private Object stripeLock(String key) {
        int h = (key != null ? key.hashCode() : 0);
        int idx = (h & 0x7fffffff) % lockStripes.length;
        return lockStripes[idx];
    }

    private <T> T withLock(String key, Supplier<T> fn) {
        Object lock = stripeLock(key);
        synchronized (lock) {
            return fn.get();
        }
    }
}