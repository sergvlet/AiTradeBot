package com.chicu.aitradebot.ml.tuning.eval.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StrategyEnvResolverImpl implements StrategyEnvResolver {

    private final StrategySettingsService strategySettingsService;

    @Override
    public Env resolve(long chatId, StrategyType type) {
        StrategySettings s = strategySettingsService.findAllByChatId(chatId, null, null)
                .stream()
                .filter(x -> x.getType() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("StrategySettings not found: chatId=" + chatId + ", type=" + type));

        if (s.getExchangeName() == null || s.getNetworkType() == null) {
            throw new IllegalStateException("exchange/network is null in StrategySettings (chatId=" + chatId + ", type=" + type + ")");
        }

        return new Env(s.getExchangeName(), s.getNetworkType());
    }
}
