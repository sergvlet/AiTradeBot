package com.chicu.aitradebot.web.ui;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.web.ui.entity.UiStrategyLayerEntity;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Locale;

public class UiStrategyLayer {

    private Long id;
    private Long chatId;
    private StrategyType strategyType;
    private String symbol;
    private String type;

    // произвольные данные слоя (обычно JSON)
    private String payload;

    private Instant createdAt;
    private Instant expiresAt;

    // ===== getters/setters =====

    public Long getId() { return id; }
    public UiStrategyLayer setId(Long id) { this.id = id; return this; }

    public Long getChatId() { return chatId; }
    public UiStrategyLayer setChatId(Long chatId) { this.chatId = chatId; return this; }

    public StrategyType getStrategyType() { return strategyType; }
    public UiStrategyLayer setStrategyType(StrategyType strategyType) { this.strategyType = strategyType; return this; }

    public String getSymbol() { return symbol; }
    public UiStrategyLayer setSymbol(String symbol) { this.symbol = symbol; return this; }

    public String getType() { return type; }
    public UiStrategyLayer setType(String type) { this.type = type; return this; }

    public String getPayload() { return payload; }
    public UiStrategyLayer setPayload(String payload) { this.payload = payload; return this; }

    public Instant getCreatedAt() { return createdAt; }
    public UiStrategyLayer setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }

    public Instant getExpiresAt() { return expiresAt; }
    public UiStrategyLayer setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }

    // ===== mapping =====

    public UiStrategyLayerEntity toEntity() {
        UiStrategyLayerEntity e = new UiStrategyLayerEntity();

        // базовые поля (пробуем разные имена сеттеров — на случай отличий)
        trySet(e, Long.class, id, "setId");
        trySet(e, Long.class, chatId, "setChatId");
        trySet(e, StrategyType.class, strategyType, "setStrategyType", "setStrategy");
        trySet(e, String.class, normSymbol(symbol), "setSymbol");
        trySet(e, String.class, normType(type), "setType");
        trySet(e, String.class, payload, "setPayload", "setJson", "setData", "setValue");
        trySet(e, Instant.class, createdAt, "setCreatedAt");
        trySet(e, Instant.class, expiresAt, "setExpiresAt");

        return e;
    }

    public static UiStrategyLayer fromEntity(UiStrategyLayerEntity e) {
        UiStrategyLayer l = new UiStrategyLayer();
        if (e == null) return l;

        l.id = safeGetLong(e, "getId");
        l.chatId = safeGetLong(e, "getChatId");
        l.strategyType = safeGetEnum(e, StrategyType.class, "getStrategyType", "getStrategy");
        l.symbol = safeGetString(e, "getSymbol");
        l.type = safeGetString(e, "getType");
        l.payload = safeGetString(e, "getPayload", "getJson", "getData", "getValue");
        l.createdAt = safeGetInstant(e, "getCreatedAt");
        l.expiresAt = safeGetInstant(e, "getExpiresAt");

        return l;
    }

    private static String normSymbol(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static String normType(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static <T> void trySet(Object target, Class<T> argType, T value, String... methodNames) {
        if (target == null || value == null || methodNames == null) return;
        for (String name : methodNames) {
            if (name == null || name.isBlank()) continue;
            try {
                Method m = target.getClass().getMethod(name, argType);
                m.invoke(target, value);
                return;
            } catch (Exception ignored) {}
        }
    }

    private static String safeGetString(Object o, String... names) {
        Object v = safeInvoke(o, names);
        return v != null ? String.valueOf(v) : null;
    }

    private static Long safeGetLong(Object o, String... names) {
        Object v = safeInvoke(o, names);
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        try { return v != null ? Long.parseLong(String.valueOf(v)) : null; } catch (Exception ignored) {}
        return null;
    }

    private static Instant safeGetInstant(Object o, String... names) {
        Object v = safeInvoke(o, names);
        if (v instanceof Instant i) return i;
        return null;
    }

    private static <E extends Enum<E>> E safeGetEnum(Object o, Class<E> enumType, String... names) {
        Object v = safeInvoke(o, names);
        if (enumType.isInstance(v)) return enumType.cast(v);
        if (v != null) {
            try { return Enum.valueOf(enumType, String.valueOf(v)); } catch (Exception ignored) {}
        }
        return null;
    }

    private static Object safeInvoke(Object target, String... names) {
        if (target == null || names == null) return null;
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            try {
                Method m = target.getClass().getMethod(n);
                return m.invoke(target);
            } catch (Exception ignored) {}
        }
        return null;
    }
}