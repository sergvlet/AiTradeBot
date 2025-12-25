package com.chicu.aitradebot.web.controller.api;

import com.chicu.aitradebot.account.AccountBalanceService;
import com.chicu.aitradebot.account.AccountBalanceSnapshot;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/strategy/settings")
public class StrategySettingsApiController {

    private final StrategySettingsService strategySettingsService;
    private final AccountBalanceService accountBalanceService;

    @PostMapping("/asset")
    public AccountBalanceSnapshot changeAsset(
            @RequestParam long chatId,
            @RequestParam StrategyType type,
            @RequestParam String exchange,
            @RequestParam NetworkType network,
            @RequestParam String asset
    ) {
        StrategySettings s =
                strategySettingsService.getOrCreate(chatId, type, exchange, network);

        s.setAccountAsset(asset.toUpperCase());
        strategySettingsService.save(s);

        return accountBalanceService.getSnapshot(
                chatId, type, exchange, network
        );
    }
}
