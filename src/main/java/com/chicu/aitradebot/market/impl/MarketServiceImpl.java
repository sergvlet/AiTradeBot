package com.chicu.aitradebot.market.impl;

import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.market.MarketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketServiceImpl implements MarketService {

    private final ExchangeClientFactory exchangeClientFactory;

    // --------------------------------------------------------------
    // 🟢 Цена конкретного символа на нужной бирже
    // --------------------------------------------------------------
    @Override
    public BigDecimal getCurrentPrice(Long chatId, String symbol) {
        symbol = normalize(symbol);

        try {
            ExchangeClient client = exchangeClientFactory.getByChat(chatId);

            double price = client.getPrice(symbol);

            return BigDecimal.valueOf(price);

        } catch (Exception e) {
            log.error("❌ Ошибка getCurrentPrice chatId={} symbol={}: {}",
                    chatId, symbol, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    // --------------------------------------------------------------
    // 🟢 История свечей — строго для выбранной биржи и символа
    // --------------------------------------------------------------
    @Override
    public List<ExchangeClient.Kline> loadKlines(Long chatId,
                                                 String symbol,
                                                 String interval,
                                                 int limit) {

        symbol = normalize(symbol);

        try {
            ExchangeClient client = exchangeClientFactory.getByChat(chatId);

            List<ExchangeClient.Kline> klines =
                    client.getKlines(symbol, interval, limit);

            if (klines == null || klines.isEmpty()) {
                log.warn("⚠ Пустой ответ getKlines chatId={} symbol={} interval={}",
                        chatId, symbol, interval);
                return List.of();
            }

            return klines;

        } catch (Exception e) {
            log.error("❌ Ошибка loadKlines chatId={} symbol={} interval={} — {}",
                    chatId, symbol, interval, e.getMessage());
            return List.of();
        }
    }

    // --------------------------------------------------------------
    // 🔧 Универсальная нормализация символов (ETH/USDT → ETHUSDT)
    // --------------------------------------------------------------
    private String normalize(String s) {
        if (s == null) return "";
        return s.replace("/", "").trim().toUpperCase();
    }
}
