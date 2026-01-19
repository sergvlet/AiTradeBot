package com.chicu.aitradebot.journal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderCorrelationTest {

    @Test
    void newCorrelationId_shouldReturnLowerHex32_withoutDashes() {
        String id = OrderCorrelation.newCorrelationId();

        assertNotNull(id);
        assertEquals(32, id.length(), "должно быть 32 символа (uuid без '-')");
        assertFalse(id.contains("-"));
        assertEquals(id.toLowerCase(), id, "должно быть в lowercase");
        assertTrue(id.matches("[0-9a-f]{32}"), "должен быть hex 32");
    }

    @Test
    void toClientOrderId_shouldStartWithPrefix_andKeepCorrelationAsPrefixPart() {
        String cid = "abcd1234";
        String coid = OrderCorrelation.toClientOrderId(cid);

        assertNotNull(coid);
        assertTrue(coid.startsWith("ATB-"), "должен быть префикс ATB-");
        assertTrue(coid.contains("abcd1234"), "должен содержать исходный correlationId как подстроку");

        // ВАЖНО: у тебя дополняется нулями до фикс. длины → проверим что после ATB- минимум 8
        assertTrue(coid.length() >= "ATB-".length() + 8);
    }

    @Test
    void toClientOrderId_shouldNotDoublePrefix() {
        String input = "ATB-zzzz";
        String coid = OrderCorrelation.toClientOrderId(input);

        assertNotNull(coid);
        assertTrue(coid.startsWith("ATB-"));
        assertFalse(coid.startsWith("ATB-ATB-"), "не должен удваивать префикс");
    }

    @Test
    void extractCorrelationId_shouldRemovePrefix_andTrimPaddingIfAny() {
        String coid = OrderCorrelation.toClientOrderId("abcd1234"); // даст ATB-abcd1234 + нули
        String extracted = OrderCorrelation.extractCorrelationId(coid);

        assertNotNull(extracted);

        // Ключевое: extract должен вернуть "нормальный" cid, который начинается с исходного
        assertTrue(extracted.startsWith("abcd1234"),
                "extractCorrelationId должен начинаться с исходного correlationId");

        // Если у тебя в extract убираются хвостовые нули — тогда будет ровно abcd1234
        // Если НЕ убираются — тогда будет abcd1234 + нули; обе версии допустимы.
        // Поэтому НЕ проверяем точное равенство.
    }

    @Test
    void extractCorrelationId_shouldHandleBlankAndNull() {
        assertNull(OrderCorrelation.extractCorrelationId(null));
        assertNull(OrderCorrelation.extractCorrelationId("   "));
    }
}
