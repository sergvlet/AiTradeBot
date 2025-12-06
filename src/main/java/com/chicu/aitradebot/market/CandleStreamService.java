package com.chicu.aitradebot.market;

import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandleStreamService {

    private final MarketService marketService;
    private final SmartFusionStrategySettingsService sfSettingsService;

    /**
     * 📌 Получить real-time свечи для Smart Fusion через MarketService v4.
     */
    public List<ExchangeClient.Kline> getSmartFusionCandles(Long chatId) {
        SmartFusionStrategySettings sf = (SmartFusionStrategySettings)
                sfSettingsService.findByChatId(chatId)
                        .orElseThrow(() -> new IllegalStateException("SmartFusion settings not found"));

        String symbol = sf.getSymbol();
        String tf = sf.getTimeframe();
        int limit = sf.getCandleLimit();

        try {
            List<ExchangeClient.Kline> list =
                    marketService.loadKlines(chatId, symbol, tf, limit);

            if (list == null || list.isEmpty())
                log.warn("⚠ Нет свечей: chatId={} {} {}", chatId, symbol, tf);

            return list;

        } catch (Exception e) {
            log.error("❌ Ошибка получения свечей: chatId={} {} {}: {}",
                    chatId, symbol, tf, e.getMessage());
            return List.of();
        }
    }
}
