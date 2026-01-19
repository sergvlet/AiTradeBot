package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class StrategyEnvResolverImpl implements StrategyEnvResolver {

    private final StrategySettingsService strategySettingsService;

    @Override
    public Env resolve(long chatId, StrategyType type) {

        // 1) active=true должно быть первым → ключ "inactive" (true/false), false идёт раньше true
        Function<StrategySettings, Boolean> isInactive =
                s -> !Boolean.TRUE.equals(s.isActive());

        // 2) свежесть: updatedAt DESC, затем id DESC
        Comparator<StrategySettings> byFreshDesc =
                Comparator.comparing(
                                StrategySettings::getUpdatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .thenComparing(
                                StrategySettings::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .reversed();

        Comparator<StrategySettings> pickBest =
                Comparator.comparing(isInactive)   // false (active) раньше true (inactive)
                        .thenComparing(byFreshDesc);

        StrategySettings s = strategySettingsService.findAllByChatId(chatId, null, null)
                .stream()
                .filter(x -> x.getType() == type)
                .sorted(pickBest)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings not found: chatId=" + chatId + ", type=" + type
                ));

        if (s.getExchangeName() == null || s.getNetworkType() == null) {
            throw new IllegalStateException(
                    "exchange/network is null in StrategySettings (chatId=" + chatId + ", type=" + type + ")"
            );
        }

        return new Env(s.getExchangeName(), s.getNetworkType());
    }
}
