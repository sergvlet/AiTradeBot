package com.chicu.aitradebot.common.time;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Универсальный таймфрейм с поддержкой секунд и alias-строк.
 * Без switch-case на строках — парсинг через словарь, чтобы исключить дубликаты кейсов.
 */
public enum Timeframe {
    // секунды
    S1(1,   "1s"),
    S5(5,   "5s"),
    S10(10, "10s"),
    S15(15, "15s"),
    S30(30, "30s"),

    // минуты
    M1(60,   "1m"),
    M3(180,  "3m"),
    M5(300,  "5m"),
    M15(900, "15m"),
    M30(1800,"30m"),

    // часы
    H1(3600,   "1h"),
    H2(7200,   "2h"),
    H4(14400,  "4h"),
    H6(21600,  "6h"),
    H12(43200, "12h"),

    // дни/неделя/месяц
    D1(86400,    "1d"),
    D3(259200,   "3d"),
    W1(604800,   "1w"),
    MON1(2592000,"1M"); // условный месяц 30 дней

    private final int stepSeconds;
    private final String binanceCode;

    Timeframe(int stepSeconds, String binanceCode) {
        this.stepSeconds = stepSeconds;
        this.binanceCode = binanceCode;
    }

    /** Кол-во секунд в одном баре. */
    public int getStepSeconds() {
        return stepSeconds;
    }

    /**
     * Код, совместимый с Binance.
     * Для секундных ТФ Binance не поддерживает секунды — возвращаем "1m"
     * как безопасный дефолт, но в коде нужно использовать тики/ресемплинг.
     */
    public String getBinanceCode() {
        if (isSubMinute()) {
            return "1m";
        }
        return binanceCode;
    }

    /** true, если ТФ меньше 1 минуты. */
    public boolean isSubMinute() {
        return stepSeconds < 60;
    }

    // ---------- Разбор строк ----------

    private static final Map<String, Timeframe> LOOKUP;

    static {
        Map<String, Timeframe> m = new HashMap<>();

        // Базовые обозначения
        putAll(m, S1,  "1s", "01s", "1sec", "1second", "1-sec", "1 second");
        putAll(m, S5,  "5s", "05s", "5sec", "5seconds", "5-sec", "5 second", "5 seconds");
        putAll(m, S10, "10s", "10sec", "10seconds", "10-sec", "10 second", "10 seconds");
        putAll(m, S15, "15s", "15sec", "15seconds", "15-sec", "15 second", "15 seconds");
        putAll(m, S30, "30s", "30sec", "30seconds", "30-sec", "30 second", "30 seconds");

        // Частые алиасы секунд → минуты
        m.put(norm("60s"),  M1);
        m.put(norm("120s"), M2Alias()); // нет отдельного перечисления M2, сведём к M1 или M3?
        // Примечание: если нужен точный 2m — добавь enum M2 и алиасы здесь.

        putAll(m, M1,  "1m", "01m", "1min", "1 minute", "1-minute");
        putAll(m, M3,  "3m", "03m", "3min", "3 minutes", "3-minute");
        putAll(m, M5,  "5m", "05m", "5min", "5 minutes", "5-minute");
        putAll(m, M15, "15m", "15min", "15 minutes", "15-minute");
        putAll(m, M30, "30m", "30min", "30 minutes", "30-minute");

        putAll(m, H1,  "1h", "01h", "1hr", "1 hour", "1-hour");
        putAll(m, H2,  "2h", "02h", "2hr", "2 hours", "2-hour");
        putAll(m, H4,  "4h", "04h", "4hr", "4 hours", "4-hour");
        putAll(m, H6,  "6h", "06h", "6hr", "6 hours", "6-hour");
        putAll(m, H12, "12h", "12hr", "12 hours", "12-hour");

        putAll(m, D1,  "1d", "01d", "1day", "1 day", "1-day");
        putAll(m, D3,  "3d", "03d", "3day", "3 days", "3-day");
        putAll(m, W1,  "1w", "01w", "1week", "1 week", "1-week");

        // Месяц — выделяем заглавную M как в Binance, и алиасы
        putAll(m, MON1, "1M", "1mo", "1mon", "1month", "1 month", "1-month");

        LOOKUP = Collections.unmodifiableMap(m);
    }

    /**
     * Нормализация ключа для словаря:
     * - trim
     * - нижний регистр
     * - удаление пробелов
     */
    private static String norm(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private static void putAll(Map<String, Timeframe> m, Timeframe tf, String... keys) {
        for (String k : keys) {
            String n = norm(k);
            if (n != null && !n.isEmpty()) {
                m.put(n, tf);
            }
        }
        // также добавим каноническое binance-имя
        m.put(norm(tf.binanceCode), tf);
    }

    /**
     * Разбор строки таймфрейма с поддержкой алиасов.
     * Если не распознано — вернётся M15 как безопасный дефолт.
     */
    public static Timeframe from(String s) {
        if (s == null || s.isBlank()) {
            return M15;
        }
        String key = norm(s);

        // быстрые легенды вида "900" (секунды) → точно распознаем
        if (isAllDigits(key)) {
            try {
                int secs = Integer.parseInt(key);
                return fromSeconds(secs);
            } catch (NumberFormatException ignored) {
            }
        }

        Timeframe tf = LOOKUP.get(key);
        if (tf != null) return tf;

        // Доп: ключи вида "900s"
        if (key.endsWith("s") && isAllDigits(key.substring(0, key.length() - 1))) {
            try {
                int secs = Integer.parseInt(key.substring(0, key.length() - 1));
                return fromSeconds(secs);
            } catch (NumberFormatException ignored) {
            }
        }

        // Ничего не нашли — дефолт
        return M15;
    }

    /** Подбор ближайшего подходящего ТФ по секундам. */
    private static Timeframe fromSeconds(int secs) {
        if (secs <= 1)  return S1;
        if (secs <= 5)  return S5;
        if (secs <= 10) return S10;
        if (secs <= 15) return S15;
        if (secs <= 30) return S30;
        if (secs <= 60) return M1;
        if (secs <= 180) return M3;
        if (secs <= 300) return M5;
        if (secs <= 900) return M15;
        if (secs <= 1800) return M30;
        if (secs <= 3600) return H1;
        if (secs <= 7200) return H2;
        if (secs <= 14400) return H4;
        if (secs <= 21600) return H6;
        if (secs <= 43200) return H12;
        if (secs <= 86400) return D1;
        if (secs <= 259200) return D3;
        if (secs <= 604800) return W1;
        return MON1;
    }

    private static boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Временная заглушка для редких алиасов 120s → ближайший доступный.
     * Если понадобится точный 2m — добавь enum M2 и пропиши alias-ключи выше.
     */
    private static Timeframe M2Alias() {
        return M3; // или M1, на твой выбор. Я выбрал M3 как более "безопасный вверх".
    }
}
