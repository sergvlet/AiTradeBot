package com.chicu.aitradebot.market.ws;

import com.chicu.aitradebot.domain.OrderEntity;
import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Сервис-обёртка для отправки realtime событий:
 *  - свечи → CandleWebSocketHandler
 *  - трейды → TradeWebSocketHandler
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeStreamService {

    private final CandleWebSocketHandler candleHandler;
    private final TradeWebSocketHandler tradeHandler;

    // =============================================================
    // 📌 1) BROADCAST TRADE (для OrderServiceImpl)
    // =============================================================
    public void sendTrade(OrderEntity e) {
        try {
            long chatId = e.getChatId();

            Map<String,Object> map = new LinkedHashMap<>();
            map.put("id", e.getId());
            map.put("symbol", e.getSymbol());
            map.put("time", e.getTimestamp());
            map.put("side", e.getSide());
            map.put("price", e.getPrice());
            map.put("qty", e.getQuantity());
            map.put("status", e.getStatus());
            map.put("tpPrice", e.getTakeProfitPrice());
            map.put("slPrice", e.getStopLossPrice());
            map.put("strategyType", e.getStrategyType());

            tradeHandler.broadcastTrade(chatId, e.getSymbol(), map);

        } catch (Exception ex) {
            log.error("❌ sendTrade error for orderId {}: {}", e.getId(), ex.getMessage());
        }
    }


    // =============================================================
    // 📌 2) BROADCAST CANDLE (для SmartFusionCandleService)
    // =============================================================
    public void sendCandle(String symbol, String timeframe, SmartFusionCandleService.Candle c) {
        try {
            candleHandler.broadcastTick(symbol, timeframe, c);
        } catch (Exception ex) {
            log.error("❌ sendCandle error {} {}: {}", symbol, timeframe, ex.getMessage());
        }
    }

}
