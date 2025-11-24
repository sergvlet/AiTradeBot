package com.chicu.aitradebot.web.controller.api;

import java.math.BigDecimal;
import java.util.List;

public interface WebBalanceFacade {

    BalanceInfo getTotalBalance(Long chatId);

    List<AssetBalance> getAssets(Long chatId);

    // DTO
    record BalanceInfo(
            BigDecimal total,
            BigDecimal free,
            BigDecimal locked
    ) {}

    record AssetBalance(
            String asset,
            BigDecimal free,
            BigDecimal locked
    ) {}
}
