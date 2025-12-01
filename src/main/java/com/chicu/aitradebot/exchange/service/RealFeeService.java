package com.chicu.aitradebot.exchange.service;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.exchange.client.ExchangeClient;
import com.chicu.aitradebot.exchange.client.ExchangeClientFactory;
import com.chicu.aitradebot.exchange.model.AccountInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RealFeeService {

    private final ExchangeClientFactory clientFactory;

    /**
     * Загружает реальные комиссии биржи и вычисляет:
     * - VIP уровень
     * - наличие BNB (скидка)
     * - maker/taker комиссии
     */
    public FeeResult loadRealFee(long chatId, NetworkType networkType) {

        try {
            // Берём клиента биржи по имени и сети
            ExchangeClient client = clientFactory.get("BINANCE", networkType);
            if (client == null) {
                return new FeeResult(false, "Exchange client not found", 0, false, 0.1, 0.1);
            }

            // ВЫЗОВ БЕЗ типов в аргументах
            AccountInfo info = client.getAccountInfo(chatId, networkType);
            if (info == null) {
                return new FeeResult(false, "Account info is null", 0, false, 0.1, 0.1);
            }

            boolean hasBNB = info.isUsingBnbDiscount();

            // Maker fee
            double maker = hasBNB ? info.getMakerFeeWithDiscount() : info.getMakerFee();

            // Taker fee
            double taker = hasBNB ? info.getTakerFeeWithDiscount() : info.getTakerFee();

            return new FeeResult(
                    true,
                    null,                    // ошибок нет
                    info.getVipLevel(),
                    hasBNB,
                    maker,
                    taker
            );

        } catch (Exception e) {
            log.error("Ошибка загрузки комиссии: {}", e.getMessage(), e);
            return new FeeResult(false, e.getMessage(), 0, false, 0.1, 0.1);
        }
    }

    /**
     * Унифицированная структура результата.
     */
    public record FeeResult(
            boolean ok,
            String error,   // текст ошибки или null
            int vipLevel,
            boolean hasBnb,
            double maker,   // maker fee %
            double taker    // taker fee %
    ) {}
}
