package com.chicu.aitradebot.market;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
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

    private final ExchangeClientFactory clientFactory;
    private final SmartFusionStrategySettingsService sfSettingsService;

    /**
     * 📌 Получить real-time свечи для Smart Fusion
     */
    public List<ExchangeClient.Kline> getSmartFusionCandles(Long chatId) {
        SmartFusionStrategySettings sf = (SmartFusionStrategySettings) sfSettingsService.findByChatId(chatId)
                .orElseThrow(() -> new IllegalStateException("SmartFusion settings not found"));

        String exchange = sf.getExchange();       // BINANCE / BYBIT
        NetworkType network = sf.getNetworkType(); // MAINNET / TESTNET
        String symbol = sf.getSymbol();
        String tf = sf.getTimeframe();
        int limit = sf.getCandleLimit();

        ExchangeClient client = clientFactory.getClient(exchange, network);

        try {
            List<ExchangeClient.Kline> list = client.getKlines(symbol, tf, limit);
            if (list == null || list.isEmpty())
                log.warn("⚠ Клин нет: {} {} {}", exchange, symbol, tf);

            return list;

        } catch (Exception e) {
            log.error("❌ Ошибка получения свечей {} {} {}: {}", exchange, symbol, tf, e.getMessage());
            return List.of();
        }
    }
}
