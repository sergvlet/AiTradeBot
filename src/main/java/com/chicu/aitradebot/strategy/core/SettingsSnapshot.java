package com.chicu.aitradebot.strategy.core;

import lombok.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot настроек стратегии.
 * Нужен, чтобы стратегия НЕ таскала за собой JPA/EntityManager и не долбила БД на каждый тик.
 */
@Getter
@ToString
@EqualsAndHashCode
public class SettingsSnapshot {

    private final long chatId;

    /**
     * Произвольные параметры стратегии (key-value).
     * Храним immutable.
     */
    private final Map<String, Object> values;

    private SettingsSnapshot(long chatId, Map<String, Object> values) {
        this.chatId = chatId;
        this.values = values != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(values))
                : Collections.emptyMap();
    }

    // =========================================================
    // Builder
    // =========================================================
    public static SettingsSnapshotBuilder builder() {
        return new SettingsSnapshotBuilder();
    }

    public static final class SettingsSnapshotBuilder {

        private long chatId;
        private final Map<String, Object> values = new LinkedHashMap<>();

        public SettingsSnapshotBuilder chatId(long chatId) {
            this.chatId = chatId;
            return this;
        }

        /**
         * ✅ ТО САМОЕ put(), которого тебе не хватает.
         * int/double/boolean будут автозапакованы в Integer/Double/Boolean.
         */
        public SettingsSnapshotBuilder put(String key, Object value) {
            if (key == null || key.isBlank()) return this;
            if (value == null) return this;
            values.put(key, value);
            return this;
        }

        /**
         * Иногда удобно одним вызовом добавить несколько значений.
         */
        public SettingsSnapshotBuilder putAll(Map<String, ?> map) {
            if (map == null || map.isEmpty()) return this;
            map.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null) {
                    values.put(k, v);
                }
            });
            return this;
        }

        public SettingsSnapshot build() {
            return new SettingsSnapshot(chatId, values);
        }
    }

    // =========================================================
    // Helpers (опционально, но удобно)
    // =========================================================
    public Object get(String key) {
        return values.get(key);
    }

    public Integer getInt(String key) {
        Object v = values.get(key);
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        return null;
    }

    public Double getDouble(String key) {
        Object v = values.get(key);
        if (v instanceof Double d) return d;
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }

    public String getString(String key) {
        Object v = values.get(key);
        return v != null ? String.valueOf(v) : null;
    }
}
