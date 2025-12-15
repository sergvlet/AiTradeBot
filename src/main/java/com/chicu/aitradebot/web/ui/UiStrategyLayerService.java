package com.chicu.aitradebot.web.ui;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.web.ui.entity.UiStrategyLayerEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class UiStrategyLayerService {

    private final UiStrategyLayerRepository repository;
    private final ObjectMapper objectMapper;

    // =====================================================
    // TTL
    // =====================================================
    private static final Duration TTL = Duration.ofHours(24);

    // =====================================================
    // 📊 READ — ВСЕ СЛОИ (история, если нужна)
    // =====================================================
    @Transactional(readOnly = true)
    public List<UiStrategyLayerEntity> loadForChart(
            Long chatId,
            StrategyType strategyType,
            String symbol
    ) {
        if (chatId == null || strategyType == null || symbol == null) {
            return List.of();
        }
        return repository.findAllForChart(chatId, strategyType, symbol);
    }

    // =====================================================
    // ✅ READ — ПОСЛЕДНИЕ СНАПШОТЫ (ТО ЧТО НУЖНО UI)
    // =====================================================

    @Transactional(readOnly = true)
    public List<Double> loadLatestLevels(
            Long chatId,
            StrategyType strategyType,
            String symbol
    ) {
        return loadLatest(chatId, strategyType, symbol, "LEVELS")
                .flatMap(this::parseLevels)
                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadLatestZone(
            Long chatId,
            StrategyType strategyType,
            String symbol
    ) {
        return loadLatest(chatId, strategyType, symbol, "ZONE")
                .flatMap(this::parseMap)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> loadLatestOrders(
            Long chatId,
            StrategyType strategyType,
            String symbol
    ) {
        return loadLatest(chatId, strategyType, symbol, "ORDERS")
                .flatMap(this::parseOrders)
                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadLatestTpSl(
            Long chatId,
            StrategyType strategyType,
            String symbol
    ) {
        return loadLatest(chatId, strategyType, symbol, "TPSL")
                .flatMap(this::parseMap)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadLatestBuySellZones(
            Long chatId,
            StrategyType strategyType,
            String symbol
    ) {
        return loadLatest(chatId, strategyType, symbol, "BUYSELL_ZONES")
                .flatMap(this::parseMap)
                .orElse(null);
    }

    // =====================================================
    // 🧠 SAVE — LEVELS
    // =====================================================
    @Transactional
    public void saveLevels(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            Instant candleTime,
            List<? extends Number> levels
    ) {
        if (levels == null || levels.isEmpty() || symbol == null) return;

        // ✅ ВСЕГДА один актуальный снапшот
        repository.deleteByType(chatId, strategyType, symbol, "LEVELS");

        saveLayer(
                chatId,
                strategyType,
                symbol,
                "LEVELS",
                candleTime,
                Map.of("levels", levels)
        );
    }

    // =====================================================
    // 🟠 SAVE — ZONE
    // =====================================================
    @Transactional
    public void saveZone(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            Instant candleTime,
            double top,
            double bottom,
            String color
    ) {
        if (symbol == null) return;

        repository.deleteByType(chatId, strategyType, symbol, "ZONE");

        saveLayer(
                chatId,
                strategyType,
                symbol,
                "ZONE",
                candleTime,
                Map.of(
                        "top", top,
                        "bottom", bottom,
                        "color", color
                )
        );
    }

    // =====================================================
    // 🟢 SAVE — ORDERS (лимитки / рыночные)
    // payload: { orders: [ {side, price, qty, id, status} ] }
    // =====================================================
    @Transactional
    public void saveOrders(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            Instant candleTime,
            List<Map<String, Object>> orders
    ) {
        if (symbol == null || orders == null) return;

        repository.deleteByType(chatId, strategyType, symbol, "ORDERS");

        saveLayer(
                chatId,
                strategyType,
                symbol,
                "ORDERS",
                candleTime,
                Map.of("orders", orders)
        );
    }

    // =====================================================
    // 🎯 SAVE — TP / SL
    // payload: { tp, sl, colorTp, colorSl }
    // =====================================================
    @Transactional
    public void saveTpSl(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            Instant candleTime,
            Double tp,
            Double sl
    ) {
        if (symbol == null) return;

        repository.deleteByType(chatId, strategyType, symbol, "TPSL");

        Map<String, Object> payload = new LinkedHashMap<>();
        if (tp != null) payload.put("tp", tp);
        if (sl != null) payload.put("sl", sl);
        payload.put("colorTp", "rgba(34,197,94,0.9)");
        payload.put("colorSl", "rgba(239,68,68,0.9)");

        saveLayer(chatId, strategyType, symbol, "TPSL", candleTime, payload);
    }

    // =====================================================
    // 🔴 SAVE — BUY / SELL ZONES
    // =====================================================
    @Transactional
    public void saveBuySellZones(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            Instant candleTime,
            Map<String, Object> buyZone,
            Map<String, Object> sellZone
    ) {
        if (symbol == null) return;

        repository.deleteByType(chatId, strategyType, symbol, "BUYSELL_ZONES");

        Map<String, Object> payload = new LinkedHashMap<>();
        if (buyZone != null) payload.put("buy", buyZone);
        if (sellZone != null) payload.put("sell", sellZone);

        saveLayer(chatId, strategyType, symbol, "BUYSELL_ZONES", candleTime, payload);
    }

    // =====================================================
    // 🔵 INTERNAL SAVE
    // =====================================================
    private void saveLayer(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            String layerType,
            Instant candleTime,
            Object payloadObj
    ) {
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

            log.debug("💾 UI layer saved type={} chatId={} symbol={}",
                    layerType, chatId, symbol);

        } catch (JsonProcessingException e) {
            log.error("❌ Failed to serialize UI layer payload {}", payloadObj, e);
        }
    }

    // =====================================================
    // 🔍 INTERNAL LOAD
    // =====================================================
    private Optional<UiStrategyLayerEntity> loadLatest(
            Long chatId,
            StrategyType strategyType,
            String symbol,
            String layerType
    ) {
        if (chatId == null || strategyType == null || symbol == null || layerType == null) {
            return Optional.empty();
        }

        return repository.findLatestByType(chatId, strategyType, symbol, layerType)
                .stream()
                .findFirst();
    }

    // =====================================================
    // 🧩 JSON PARSERS
    // =====================================================
    private Optional<Map<String, Object>> parseMap(UiStrategyLayerEntity e) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map =
                    objectMapper.readValue(e.getPayload(), Map.class);
            return Optional.of(map);
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
        return repository.deleteOlderThan(before);
    }

    @Transactional
    public void clearStrategy(
            Long chatId,
            StrategyType strategyType,
            String symbol
    ) {
        if (symbol == null) return;
        repository.deleteForStrategy(chatId, strategyType, symbol);
    }
}
