package com.chicu.aitradebot.web.ui;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
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

    public static final String TYPE_LEVELS = "LEVELS";
    public static final String TYPE_ZONE = "ZONE";
    public static final String TYPE_TP_SL = "TP_SL";
    public static final String TYPE_WINDOW_ZONE = "WINDOW_ZONE";
    public static final String TYPE_PRICE_LINES = "PRICE_LINES";
    public static final String TYPE_TRADES = "TRADES";

    private static final Duration DEFAULT_TTL = Duration.ofDays(14);
    private static final int MAX_TRADES = 300;

    private final UiStrategyLayerRepository repo;
    private final ObjectMapper objectMapper;

    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearPriceLines(Long chatId, StrategyType strategyType, String symbol) {
        clearByType(chatId, strategyType, symbol, TYPE_PRICE_LINES);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertPriceLine(Long chatId,
                                StrategyType strategyType,
                                String symbol,
                                Instant candleTime,
                                String name,
                                Double price,
                                String color) {
        String sym = normSymbol(symbol);
        String lineName = normName(name);
        if (chatId == null || strategyType == null || sym == null) return;
        if (lineName == null || price == null || !Double.isFinite(price)) {
            clearPriceLines(chatId, strategyType, sym);
            return;
        }

        withLock(lockKey(chatId, strategyType, sym), () -> {
            List<Map<String, Object>> lines = readListPayload(chatId, strategyType, sym, TYPE_PRICE_LINES);
            LinkedHashMap<String, Map<String, Object>> byName = new LinkedHashMap<>();
            for (Map<String, Object> row : lines) {
                String n = normName(stringValue(row.get("name")));
                Double p = toDouble(row.get("price"));
                if (n == null || p == null || !Double.isFinite(p)) continue;
                LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
                copy.put("name", n);
                copy.put("price", p);
                String c = stringValue(row.get("color"));
                if (c != null && !c.isBlank()) copy.put("color", c);
                byName.put(n, copy);
            }

            LinkedHashMap<String, Object> current = new LinkedHashMap<>();
            current.put("name", lineName);
            current.put("price", price);
            if (color != null && !color.isBlank()) current.put("color", color);
            byName.put(lineName, current);

            saveLayer(chatId, strategyType, sym, TYPE_PRICE_LINES, candleTime, toJson(new ArrayList<>(byName.values())));
            return null;
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearTrades(Long chatId, StrategyType strategyType, String symbol) {
        clearByType(chatId, strategyType, symbol, TYPE_TRADES);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendTrade(Long chatId,
                            StrategyType strategyType,
                            String symbol,
                            Instant candleTime,
                            String side,
                            Double price,
                            Double qty) {
        String sym = normSymbol(symbol);
        String safeSide = normName(side);
        if (chatId == null || strategyType == null || sym == null) return;
        if (safeSide == null || price == null || !Double.isFinite(price)) return;

        withLock(lockKey(chatId, strategyType, sym), () -> {
            List<Map<String, Object>> trades = readListPayload(chatId, strategyType, sym, TYPE_TRADES);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : trades) {
                if (row == null || row.isEmpty()) continue;
                out.add(new LinkedHashMap<>(row));
            }

            LinkedHashMap<String, Object> trade = new LinkedHashMap<>();
            trade.put("side", safeSide);
            trade.put("price", price);
            if (qty != null && Double.isFinite(qty)) trade.put("qty", qty);
            trade.put("time", (candleTime != null ? candleTime : Instant.now()).toEpochMilli());
            out.add(trade);

            if (out.size() > MAX_TRADES) {
                out = new ArrayList<>(out.subList(out.size() - MAX_TRADES, out.size()));
            }

            saveLayer(chatId, strategyType, sym, TYPE_TRADES, candleTime, toJson(out));
            return null;
        });
    }

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

    public StrategyChartDto.Layers buildLatestLayersForSnapshot(Long chatId, StrategyType strategyType, String symbol) {
        String sym = normSymbol(symbol);
        if (chatId == null || strategyType == null || sym == null) return StrategyChartDto.Layers.empty();

        StrategyChartDto.Layers.LayersBuilder builder = StrategyChartDto.Layers.builder()
                .levels(List.of())
                .zone(null)
                .tpSl(null)
                .windowZone(null)
                .priceLines(List.of())
                .trades(List.of());

        findLatestByType(chatId, strategyType, sym, TYPE_LEVELS)
                .flatMap(this::parsePayloadAny)
                .ifPresent(v -> builder.levels(toDoublePriceList(v)));

        findLatestByType(chatId, strategyType, sym, TYPE_ZONE)
                .flatMap(this::parsePayloadAny)
                .map(this::toZone)
                .ifPresent(builder::zone);

        findLatestByType(chatId, strategyType, sym, TYPE_TP_SL)
                .flatMap(this::parsePayloadAny)
                .map(this::toTpSl)
                .ifPresent(builder::tpSl);

        findLatestByType(chatId, strategyType, sym, TYPE_WINDOW_ZONE)
                .flatMap(this::parsePayloadAny)
                .map(this::toWindowZone)
                .ifPresent(builder::windowZone);

        findLatestByType(chatId, strategyType, sym, TYPE_PRICE_LINES)
                .flatMap(this::parsePayloadAny)
                .ifPresent(v -> builder.priceLines(toPriceLines(v)));

        findLatestByType(chatId, strategyType, sym, TYPE_TRADES)
                .flatMap(this::parsePayloadAny)
                .ifPresent(v -> builder.trades(toTradeMarkers(v)));

        return builder.build();
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

    private List<Map<String, Object>> readListPayload(Long chatId,
                                                      StrategyType strategyType,
                                                      String symbol,
                                                      String layerType) {
        return findLatestByType(chatId, strategyType, symbol, layerType)
                .flatMap(this::parsePayloadAny)
                .map(this::asListOfMaps)
                .orElseGet(List::of);
    }

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

            if (TYPE_LEVELS.equals(lt) || TYPE_PRICE_LINES.equals(lt) || TYPE_TRADES.equals(lt)) {
                Object v = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
                return Optional.ofNullable(v);
            }

            Object v = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return Optional.ofNullable(v);

        } catch (Exception ex) {
            log.warn("⚠ [UI-LAYERS] payload parse failed layerType={} id={} err={}",
                    e.getLayerType(), safeId(e), ex.toString());
            return Optional.empty();
        }
    }

    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() == null) continue;
                    copy.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                out.add(copy);
            }
        }
        return out;
    }

    private List<Double> toDoublePriceList(Object value) {
        List<Double> out = new ArrayList<>();
        for (Map<String, Object> row : asListOfMaps(value)) {
            Double price = toDouble(row.get("price"));
            if (price != null && Double.isFinite(price)) out.add(price);
        }
        return out;
    }

    private StrategyChartDto.Zone toZone(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        Double top = toDouble(map.get("top"));
        Double bottom = toDouble(map.get("bottom"));
        if (top == null || bottom == null || !Double.isFinite(top) || !Double.isFinite(bottom)) return null;
        return StrategyChartDto.Zone.builder()
                .top(top)
                .bottom(bottom)
                .color(stringValue(map.get("color")))
                .build();
    }

    private StrategyChartDto.TpSl toTpSl(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        Double tp = toDouble(map.get("tp"));
        Double sl = toDouble(map.get("sl"));
        if (tp == null && sl == null) return null;
        return StrategyChartDto.TpSl.builder().tp(tp).sl(sl).build();
    }

    private StrategyChartDto.WindowZone toWindowZone(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        Double high = toDouble(map.get("high"));
        Double low = toDouble(map.get("low"));
        if (high == null || low == null || !Double.isFinite(high) || !Double.isFinite(low)) return null;
        return StrategyChartDto.WindowZone.builder().high(high).low(low).build();
    }

    private List<StrategyChartDto.PriceLine> toPriceLines(Object value) {
        List<StrategyChartDto.PriceLine> out = new ArrayList<>();
        for (Map<String, Object> row : asListOfMaps(value)) {
            String name = stringValue(row.get("name"));
            Double price = toDouble(row.get("price"));
            if (name == null || price == null || !Double.isFinite(price)) continue;
            out.add(StrategyChartDto.PriceLine.builder()
                    .name(name)
                    .price(price)
                    .color(stringValue(row.get("color")))
                    .build());
        }
        return out;
    }

    private List<StrategyChartDto.TradeMarker> toTradeMarkers(Object value) {
        List<StrategyChartDto.TradeMarker> out = new ArrayList<>();
        for (Map<String, Object> row : asListOfMaps(value)) {
            String side = normName(stringValue(row.get("side")));
            Double price = toDouble(row.get("price"));
            Long time = toLong(row.get("time"));
            if (side == null || price == null || !Double.isFinite(price) || time == null || time <= 0L) continue;
            out.add(StrategyChartDto.TradeMarker.builder()
                    .side(side)
                    .price(price)
                    .qty(toDouble(row.get("qty")))
                    .time(time)
                    .source(stringValue(row.get("source")))
                    .build());
        }
        return out;
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

    private String normName(String value) {
        if (value == null) return null;
        String s = value.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
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
