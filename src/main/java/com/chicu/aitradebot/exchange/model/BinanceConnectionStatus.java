package com.chicu.aitradebot.exchange.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Результат расширенной диагностики подключения к Binance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BinanceConnectionStatus {

    /**
     * Всё ли в порядке в целом (можно ли работать стратегиями).
     */
    private boolean ok;

    /**
     * API ключ корректен (по крайней мере, запрос /account прошёл).
     */
    private boolean keyValid;

    /**
     * Секрет корректен (подпись запроса прошла).
     */
    private boolean secretValid;

    /**
     * Есть ли права на чтение аккаунта.
     */
    private boolean readingEnabled;

    /**
     * Разрешена ли спотовая торговля по этим ключам.
     */
    private boolean tradingEnabled;

    /**
     * Разрешён ли текущий IP (если удалось понять).
     * Для TESTNET и успешного /api/v3/account считаем true.
     */
    private boolean ipAllowed;

    /**
     * Конфликт сети (например, ключи от mainnet, а мы бьёмся в testnet).
     * В текущей реализации всегда false, но оставлено на будущее.
     */
    private boolean networkMismatch;

    /**
     * Краткое сообщение для пользователя.
     */
    private String message;

    /**
     * Расширенные причины / пояснения.
     */
    @Builder.Default
    private List<String> reasons = new ArrayList<>();
}
