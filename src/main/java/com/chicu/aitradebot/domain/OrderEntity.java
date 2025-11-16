package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.strategy.smartfusion.components.SmartFusionCandleService;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/chart")
@RequiredArgsConstructor
@Slf4j
public class ChartApiController {

    private final SmartFusionCandleService candleService;

    @PersistenceContext
    private final jakarta.persistence.EntityManager em;

    /**
     * 📈 API: реальные свечи + точки BUY/SELL
     */
    @GetMapping("/data")
    public Map<String, Object> getChartData(
            @RequestParam long chatId,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "15m") String timeframe,
            @RequestParam(defaultValue = "200") int limit
    ) {
        log.info("📊 /api/chart/data chatId={}, symbol={}, tf={}, limit={}",
                chatId, symbol, timeframe, limit);

        // 1) Загружаем свечи SmartFusion
        var cfg = candleService.buildSettings(chatId, symbol, timeframe, limit);
        var candles = candleService.getCandles(cfg);

        List<Map<String, Object>> candleDtos = new ArrayList<>();
        for (var c : candles) {
            candleDtos.add(Map.of(
                    "time", c.ts().getEpochSecond(),
                    "open", c.open(),
                    "high", c.high(),
                    "low", c.low(),
                    "close", c.close()
            ));
        }

        // 2) История сделок
        List<OrderEntity> orders = em
                .createQuery("""
                     SELECT o FROM OrderEntity o 
                     WHERE o.chatId = :chatId AND o.symbol = :symbol
                     ORDER BY o.timestamp ASC
                     """, OrderEntity.class)
                .setParameter("chatId", chatId)
                .setParameter("symbol", symbol)
                .getResultList();

        List<Map<String, Object>> trades = new ArrayList<>();
        for (OrderEntity o : orders) {

            trades.add(Map.of(
                    "time", o.getTimestamp() / 1000,   // 🔥 epoch seconds
                    "side", o.getSide(),               // BUY / SELL
                    "price", o.getPrice(),
                    "qty", o.getQuantity(),
                    "total", o.getTotal()
            ));
        }

        return Map.of(
                "candles", candleDtos,
                "trades", trades
        );
    }
}
