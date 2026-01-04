package com.chicu.aitradebot.journal;

import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.experimental.UtilityClass;

import java.util.Locale;
import java.util.UUID;

@UtilityClass
public class OrderCorrelation {

    /**
     * Базовый correlationId (32 символа, hex-подобный).
     */
    public static String newCorrelationId() {
        return UUID.randomUUID().toString()
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Превращает correlationId в clientOrderId с префиксом ATB- (36 символов максимум).
     * Формат: ATB- + 32 = 36
     */
    public static String toClientOrderId(String correlationId) {
        String cid = normalizeCorrelationId(correlationId);
        if (cid == null) return null;
        return "ATB-" + cid; // 4 + 32 = 36
    }

    /**
     * ✅ То, что у тебя вызывается из OrderServiceImpl:
     * clientOrderId(correlationId, chatId, strategyType, symbol, role)
     *
     * Binance Spot newClientOrderId часто ограничен 36 символами.
     * Мы делаем формат: "ATB" + roleCode + "-" + (31 символ correlationId) = 36
     * Пример: ATBE-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
     */
    public static String clientOrderId(String correlationId,
                                       Long chatId,
                                       StrategyType strategyType,
                                       String symbol,
                                       String role) {

        String cid = normalizeCorrelationId(correlationId);
        if (cid == null) cid = newCorrelationId();

        String rc = roleCode(role); // 1 символ
        // "ATB" + rc + "-" = 5 символов, остаётся 31 на correlationId
        String cut = cid.length() > 31 ? cid.substring(0, 31) : padRight(cid, 31, '0');

        return "ATB" + rc + "-" + cut; // 36
    }

    /**
     * Достаём correlationId из clientOrderId.
     * Поддержка:
     * - ATB-<cid>
     * - ATB<role>-<cid>
     */
    public static String extractCorrelationId(String clientOrderId) {
        if (clientOrderId == null) return null;
        String s = clientOrderId.trim();
        if (s.isEmpty()) return null;

        // ATB-xxxxxxxx...
        if (s.startsWith("ATB-") && s.length() > 4) {
            return s.substring(4);
        }

        // ATBE-xxxxxxxx...
        if (s.startsWith("ATB") && s.length() > 5 && s.charAt(4) == '-') {
            return s.substring(5);
        }

        // если прилетело без префикса — считаем что это уже correlationId
        return s;
    }

    // =====================================================
    // helpers
    // =====================================================

    private static String normalizeCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return null;
        String cid = correlationId.trim().toLowerCase(Locale.ROOT);

        // если прилетело уже clientOrderId — вытащим correlationId
        if (cid.startsWith("atb")) {
            String extracted = extractCorrelationId(cid.toUpperCase(Locale.ROOT));
            if (extracted != null) cid = extracted.toLowerCase(Locale.ROOT);
        }

        // оставим только [a-z0-9] и обрежем до 32
        cid = cid.replaceAll("[^a-z0-9]", "");
        if (cid.isEmpty()) return null;

        if (cid.length() > 32) cid = cid.substring(0, 32);
        if (cid.length() < 32) cid = padRight(cid, 32, '0');
        return cid;
    }

    private static String roleCode(String role) {
        if (role == null) return "U";
        String r = role.trim().toUpperCase(Locale.ROOT);

        return switch (r) {
            case "ENTRY" -> "E";
            case "TP" -> "T";
            case "SL" -> "S";
            case "EXIT" -> "X";
            case "OCO" -> "O";
            default -> "U";
        };
    }

    private static String padRight(String s, int len, char ch) {
        if (s == null) s = "";
        if (s.length() >= len) return s;
        StringBuilder sb = new StringBuilder(len);
        sb.append(s);
        while (sb.length() < len) sb.append(ch);
        return sb.toString();
    }
}
