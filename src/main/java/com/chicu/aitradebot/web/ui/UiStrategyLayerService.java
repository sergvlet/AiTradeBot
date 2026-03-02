// src/main/java/com/chicu/aitradebot/web/ui/UiStrategyLayerService.java
package com.chicu.aitradebot.web.ui;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.web.ui.entity.UiStrategyLayerEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class UiStrategyLayerService {

    // Типы слоёв (под StrategyLiveWsBridge типы приходят как: levels/zone/tp_sl/window_zone)
    public static final String TYPE_LEVELS      = "LEVELS";
    public static final String TYPE_ZONE        = "ZONE";
    public static final String TYPE_TP_SL       = "TP_SL";
    public static final String TYPE_WINDOW_ZONE = "WINDOW_ZONE";

    // чтобы не раздувать таблицу бесконечно (можно менять)
    private static final Duration DEFAULT_TTL = Duration.ofDays(14);

    private final UiStrategyLayerRepository repo;
    private final ObjectMapper objectMapper;

    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    // =====================================================
    // API под StrategyLiveWsBridge
    // =====================================================

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearLevels(Long chatId, StrategyType strategyType, String symbol) {
        clearByType(chatId, strategyType, symbol, TYPE_LEVELS);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveLevels(Long chatId,
                           StrategyType strategyType,
                           String symbol,
                           Instant candleTime,
                           List<Double> levels) {
        if (levels == null || levels.isEmpty()) {
            clearLevels(chatId, strategyType, symbol);
            return;
        }

        // как в WS: levels=[{price:...}, ...]
        List<Map<String, Object>> payloadLevels = new ArrayList<>(levels.size());
        for (Double p : levels) {
            if (p == null) continue;
            payloadLevels.add(Map.of("price", p));
        }
        if (payloadLevels.isEmpty()) {
            clearLevels(chatId, strategyType, symbol);
            return;
        }

        saveLayer(chatId, strategyType, symbol, TYPE_LEVELS, candleTime, toJson(payloadLevels));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearZone(Long chatId, StrategyType strategyType, String symbol) {
        clearByType(chatId, strategyType, symbol, TYPE_ZONE);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveZone(Long chatId,
                         StrategyType strategyType,
                         String symbol,
                         Instant candleTime,
                         double top,
                         double bottom,
                         String color) {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("top", top);
        payload.put("bottom", bottom);
        if (color != null && !color.isBlank()) payload.put("color", color);

        saveLayer(chatId, strategyType, symbol, TYPE_ZONE, candleTime, toJson(payload));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearTpSl(Long chatId, StrategyType strategyType, String symbol) {
        clearByType(chatId, strategyType, symbol, TYPE_TP_SL);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveTpSl(Long chatId,
                         StrategyType strategyType,
                         String symbol,
                         Instant candleTime,
                         Double tp,
                         Double sl) {

        if (tp == null && sl == null) {
            clearTpSl(chatId, strategyType, symbol);
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (tp != null) payload.put("tp", tp);
        if (sl != null) payload.put("sl", sl);

        saveLayer(chatId, strategyType, symbol, TYPE_TP_SL, candleTime, toJson(payload));
    }

    // ✅ WINDOW_ZONE (для WINDOW_SCALPING / SCALPING)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearWindowZone(Long chatId, StrategyType strategyType, String symbol) {
        clearByType(chatId, strategyType, symbol, TYPE_WINDOW_ZONE);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveWindowZone(Long chatId,
                               StrategyType strategyType,
                               String symbol,
                               Instant candleTime,
                               double high,
                               double low) {

        if (!Double.isFinite(high) || !Double.isFinite(low)) {
            clearWindowZone(chatId, strategyType, symbol);
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("high", Math.max(high, low));
        payload.put("low", Math.min(high, low));

        saveLayer(chatId, strategyType, symbol, TYPE_WINDOW_ZONE, candleTime, toJson(payload));
    }

    // =====================================================
    // Универсальные методы (для replay/snapshot)
    // =====================================================

    public List<UiStrategyLayerEntity> findAll(Long chatId, StrategyType strategyType, String symbol) {
        String sym = normSymbol(symbol);
        if (chatId == null || strategyType == null || sym == null) return List.of();
        return repo.findByChatIdAndStrategyTypeAndSymbolOrderByCandleTimeAsc(chatId, strategyType, sym);
    }

    public Optional<UiStrategyLayerEntity> findLatestByType(Long chatId, StrategyType strategyType, String symbol, String layerType) {
        String sym = normSymbol(symbol);
        String lt = normType(layerType);
        if (chatId == null || strategyType == null || sym == null || lt == null) return Optional.empty();
        return repo.findTop1ByChatIdAndStrategyTypeAndSymbolAndLayerTypeOrderByCreatedAtDesc(chatId, strategyType, sym, lt);
    }

    /**
     * ✅ Готовый layers-map для REST snapshot:
     * keys: levels, zone, tpSl, windowZone
     */
    public Map<String, Object> buildLatestLayersForSnapshot(Long chatId, StrategyType strategyType, String symbol) {
        String sym = normSymbol(symbol);
        if (chatId == null || strategyType == null || sym == null) return Map.of();

        LinkedHashMap<String, Object> out = new LinkedHashMap<>();

        // levels -> "levels"
        findLatestByType(chatId, strategyType, sym, TYPE_LEVELS)
                .flatMap(this::parsePayloadAny)
                .ifPresent(v -> out.put("levels", v));

        // zone -> "zone"
        findLatestByType(chatId, strategyType, sym, TYPE_ZONE)
                .flatMap(this::parsePayloadAny)
                .ifPresent(v -> out.put("zone", v));

        // tp_sl -> "tpSl" (как ожидает JS-стратегия)
        findLatestByType(chatId, strategyType, sym, TYPE_TP_SL)
                .flatMap(this::parsePayloadAny)
                .ifPresent(v -> out.put("tpSl", v));

        // window_zone -> "windowZone"
        findLatestByType(chatId, strategyType, sym, TYPE_WINDOW_ZONE)
                .flatMap(this::parsePayloadAny)
                .ifPresent(v -> out.put("windowZone", v));

        return out;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearAll(Long chatId, StrategyType strategyType, String symbol) {
        String sym = normSymbol(symbol);
        if (chatId == null || strategyType == null || sym == null) return;

        withLock(lockKey(chatId, strategyType, sym), () -> {
            int n = repo.deleteAllByContext(chatId, strategyType, sym);
            if (n > 0) log.info("🧽 [UI-LAYERS] Очищены все слои: chatId={} type={} sym={} удалено={}", chatId, strategyType, sym, n);
            return null;
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cleanupOld(Duration ttl) {
        Duration eff = (ttl != null ? ttl : DEFAULT_TTL);
        Instant olderThan = Instant.now().minus(eff);

        int deleted = repo.deleteOlderThan(olderThan);
        if (deleted > 0) {
            log.info("🧹 [UI-LAYERS] Очистка старых слоёв: olderThan={} удалено={}", olderThan, deleted);
        }
        return deleted;
    }

    // =====================================================
    // internals
    // =====================================================

    private void clearByType(Long chatId, StrategyType strategyType, String symbol, String layerType) {
        String sym = normSymbol(symbol);
        String lt = normType(layerType);
        if (chatId == null || strategyType == null || sym == null || lt == null) return;

        withLock(lockKey(chatId, strategyType, sym), () -> {
            int n = repo.deleteByType(chatId, strategyType, sym, lt);
            if (n > 0 && log.isDebugEnabled()) {
                log.debug("🧽 [UI-LAYERS] Слой очищен: chatId={} type={} sym={} layerType={} удалено={}",
                        chatId, strategyType, sym, lt, n);
            }
            return null;
        });
    }

    private void saveLayer(Long chatId,
                           StrategyType strategyType,
                           String symbol,
                           String layerType,
                           Instant candleTime,
                           String payloadJson) {

        String sym = normSymbol(symbol);
        String lt = normType(layerType);
        if (chatId == null || strategyType == null || sym == null || lt == null) return;
        if (payloadJson == null || payloadJson.isBlank()) return;

        Instant ct = (candleTime != null ? candleTime : Instant.now());
        Instant now = Instant.now();

        withLock(lockKey(chatId, strategyType, sym), () -> {
            // держим “последний” слой одного типа: чистим старый и пишем новый
            repo.deleteByType(chatId, strategyType, sym, lt);

            UiStrategyLayerEntity e = UiStrategyLayerEntity.builder()
                    .chatId(chatId)
                    .strategyType(strategyType)
                    .symbol(sym)
                    .layerType(lt)
                    .payload(payloadJson)
                    .candleTime(ct)
                    .createdAt(now)
                    .build();

            repo.save(e);

            if (log.isDebugEnabled()) {
                log.debug("💾 [UI-LAYERS] Слой сохранён: chatId={} type={} sym={} layerType={} candleTime={}",
                        chatId, strategyType, sym, lt, ct);
            }

            return null;
        });
    }

    private Optional<Object> parsePayloadAny(UiStrategyLayerEntity e) {
        if (e == null) return Optional.empty();
        String json = e.getPayload();
        if (json == null || json.isBlank()) return Optional.empty();

        try {
            String lt = normType(e.getLayerType());

            if (TYPE_LEVELS.equals(lt)) {
                // ожидаем List<Map<String,Object>>
                Object v = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
                return Optional.ofNullable(v);
            }

            // zone/tp_sl/window_zone -> Map<String,Object>
            Object v = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return Optional.ofNullable(v);

        } catch (Exception ex) {
            log.warn("⚠ [UI-LAYERS] payload parse failed layerType={} id={} err={}",
                    e.getLayerType(), safeId(e), ex.toString());
            return Optional.empty();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("❌ [UI-LAYERS] Не удалось сериализовать JSON payload: {}", e.toString());
            return null;
        }
    }

    private String normSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private String normType(String layerType) {
        if (layerType == null) return null;
        String t = layerType.trim().toUpperCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private String lockKey(Long chatId, StrategyType strategyType, String symbol) {
        return chatId + ":" + strategyType + ":" + symbol;
    }

    private <T> T withLock(String key, java.util.concurrent.Callable<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock(true));
        lock.lock();
        try {
            return action.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    private static Object safeId(UiStrategyLayerEntity e) {
        try { return e.getId(); } catch (Exception ex) { return "n/a"; }
    }
}