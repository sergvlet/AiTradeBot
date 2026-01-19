package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.journal.TradeIntentEvent;
import com.chicu.aitradebot.market.guard.ExchangeAIGuard;
import com.chicu.aitradebot.market.guard.GuardResult;
import com.chicu.aitradebot.market.service.MarketSymbolService;
import com.chicu.aitradebot.repository.OrderRepository;
import com.chicu.aitradebot.service.OrderService;
import com.chicu.aitradebot.service.TradeJournalGateway;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import com.chicu.aitradebot.strategy.live.StrategyLivePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private StrategyLivePublisher livePublisher;
    @Mock private ExchangeClientFactory exchangeClientFactory;
    @Mock private ExchangeSettingsService exchangeSettingsService;
    @Mock private ExchangeAIGuard aiGuard;
    @Mock private MarketSymbolService marketSymbolService;
    @Mock private TradeJournalGateway tradeJournalGateway;
    @Mock private ExchangeClient exchangeClient;

    @InjectMocks
    private OrderServiceImpl service;

    @BeforeEach
    void setupCommonMocks() {
        when(exchangeClientFactory.getByChat(anyLong())).thenReturn(exchangeClient);
        when(exchangeClient.getExchangeName()).thenReturn("BINANCE");

        ExchangeSettings es = new ExchangeSettings();
        es.setEnabled(true);
        es.setExchange("BINANCE");
        es.setNetwork(NetworkType.MAINNET);
        es.setUpdatedAt(Instant.now()); // ✅ FIX: было Instant.from(LocalDateTime.now())

        when(exchangeSettingsService.findAllByChatId(anyLong())).thenReturn(List.of(es));

        when(tradeJournalGateway.recordIntent(
                anyLong(),
                any(),
                anyString(),
                any(),
                anyString(),
                anyString(),
                any(),
                any(),
                anyString(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn("cid123");
    }

    @Test
    void placeMarket_legacy_shouldSaveEntity_andCallJournal_andPublishTrade_whenGuardOk() {
        GuardResult guard = GuardResult.pass(new BigDecimal("0.010"), new BigDecimal("42000"));

        when(aiGuard.validateAndAdjust(anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(guard);

        // repository save возвращает сущность (обычно так и бывает)
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var res = service.placeMarket(
                10L,
                "BTCUSDT",
                "BUY",
                new BigDecimal("0.02"),
                new BigDecimal("43000"),
                "SCALPING"
        );

        assertNotNull(res);
        assertEquals("BTCUSDT", res.getSymbol());
        assertEquals("BUY", res.getSide());
        assertTrue(res.isFilled());

        // entity сохранён
        ArgumentCaptor<OrderEntity> cap = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderRepository, times(1)).save(cap.capture());
        OrderEntity saved = cap.getValue();

        assertEquals(10L, saved.getChatId());
        assertEquals("BTCUSDT", saved.getSymbol());
        assertEquals("BUY", saved.getSide());
        assertEquals(new BigDecimal("0.010"), saved.getQuantity());
        assertEquals(new BigDecimal("42000"), saved.getPrice());
        assertEquals("FILLED", saved.getStatus());
        assertTrue(saved.getFilled());

        // journal: intent + attach + link
        verify(tradeJournalGateway, times(1)).recordIntent(
                eq(10L),
                eq(StrategyType.SCALPING),
                eq("BINANCE"),
                eq(NetworkType.MAINNET),
                eq("BTCUSDT"),
                anyString(),
                eq(TradeIntentEvent.Signal.BUY),
                eq(TradeIntentEvent.Decision.ALLOW),
                anyString(),
                any(), any(), any(),
                any(), any(), any()
        );

        verify(tradeJournalGateway, times(1))
                .attachClientOrderId(eq("cid123"), argThat(s -> s != null && !s.isBlank()));

        verify(tradeJournalGateway, times(1)).linkClientOrder(
                eq(10L),
                eq(StrategyType.SCALPING),
                eq("BINANCE"),
                eq(NetworkType.MAINNET),
                eq("BTCUSDT"),
                anyString(),
                eq("cid123"),
                argThat(s -> s != null && !s.isBlank()),
                eq("ENTRY")
        );

        // пушим trade в лайв
        verify(livePublisher, times(1)).pushTrade(
                eq(10L),
                eq(StrategyType.SCALPING),
                eq("BTCUSDT"),
                eq("BUY"),
                eq(new BigDecimal("42000")),
                eq(new BigDecimal("0.010")),
                any()
        );
    }

    @Test
    void placeMarket_legacy_shouldThrow_andNotSave_whenGuardNotOk() {
        GuardResult guard = GuardResult.fail(
                new BigDecimal("0.02"),
                new BigDecimal("43000"),
                "BLOCKED"
        );

        when(aiGuard.validateAndAdjust(anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(guard);

        assertThrows(IllegalArgumentException.class, () -> service.placeMarket(
                10L,
                "BTCUSDT",
                "BUY",
                new BigDecimal("0.02"),
                new BigDecimal("43000"),
                "SCALPING"
        ));

        // ✅ intent пишется даже при блоке (REJECT)
        verify(tradeJournalGateway, times(1)).recordIntent(
                eq(10L),
                eq(StrategyType.SCALPING),
                eq("BINANCE"),
                eq(NetworkType.MAINNET),
                eq("BTCUSDT"),
                anyString(), // timeframe (у тебя "1m")
                eq(TradeIntentEvent.Signal.BUY),
                eq(TradeIntentEvent.Decision.REJECT),
                eq("AI_GUARD_BLOCK"),
                isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull()
        );

        // ❌ никаких ордеров/лайва при блоке
        verify(orderRepository, never()).save(any());
        verify(livePublisher, never()).pushTrade(anyLong(), any(), anyString(), anyString(), any(), any(), any());

        // ❌ clientOrderId не привязываем при REJECT
        verify(tradeJournalGateway, never()).attachClientOrderId(anyString(), anyString());
        verify(tradeJournalGateway, never()).linkClientOrder(anyLong(), any(), anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void placeMarket_context_shouldBehaveSameAsLegacy() {
        GuardResult guard = GuardResult.pass(new BigDecimal("0.005"), new BigDecimal("40000"));

        when(aiGuard.validateAndAdjust(anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(guard);

        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderService.OrderContext ctx = new OrderService.OrderContext(
                10L, StrategyType.SCALPING, "BTCUSDT", "1m", null, "ENTRY"
        );

        var res = service.placeMarket(ctx, "BUY", new BigDecimal("0.02"), new BigDecimal("41000"));

        assertNotNull(res);
        assertEquals("BTCUSDT", res.getSymbol());
        assertEquals("BUY", res.getSide());

        verify(orderRepository, times(1)).save(any(OrderEntity.class));
        verify(tradeJournalGateway, times(1)).recordIntent(
                anyLong(), any(), anyString(), any(), anyString(), anyString(),
                any(), any(), anyString(),
                any(), any(), any(),
                any(), any(), any()
        );
    }
}
