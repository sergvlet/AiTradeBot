package com.chicu.aitradebot.account;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

@Value
@Builder
public class AccountBalanceSnapshot {

    /** Активы, доступные для выбора (free > 0) */
    List<String> availableAssets;

    /** Выбранный актив (из StrategySettings.accountAsset, либо авто-выбор) */
    String selectedAsset;

    /** Доступно средств (free) для выбранного актива */
    BigDecimal selectedFreeBalance;

    /** true если успешно получили баланс с биржи */
    boolean connectionOk;

    /** опционально: текст ошибки, чтобы красиво выводить в UI/логах */
    String error;
}
