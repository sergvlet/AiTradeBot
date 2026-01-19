package com.chicu.aitradebot.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.journal.TradeIntentEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoopTradeJournalGatewayTest {

    private final NoopTradeJournalGateway gw = new NoopTradeJournalGateway();

    @Test
    void recordIntent_shouldReturnCorrelationId() {
        String cid = gw.recordIntent(
                1L,
                StrategyType.SCALPING,
                "BINANCE",
                NetworkType.MAINNET,
                "BTCUSDT",
                "1m",
                TradeIntentEvent.Signal.BUY,
                TradeIntentEvent.Decision.ALLOW,
                "OK",
                null, null, null,
                null,
                null,
                null
        );

        assertNotNull(cid);
        assertFalse(cid.isBlank());
        assertFalse(cid.contains("-"));
        assertEquals(cid.toLowerCase(), cid);
        assertEquals(32, cid.length(), "uuid без '-' должен быть 32");
    }

    @Test
    void attachClientOrderId_shouldNotThrow() {
        assertDoesNotThrow(() -> gw.attachClientOrderId("cid", "ATB-cid"));
    }

    @Test
    void linkClientOrder_shouldNotThrow() {
        assertDoesNotThrow(() -> gw.linkClientOrder(
                1L,
                StrategyType.SCALPING,
                "BINANCE",
                NetworkType.MAINNET,
                "BTCUSDT",
                "1m",
                "cid",
                "ATB-cid",
                "ENTRY"
        ));
    }
}
