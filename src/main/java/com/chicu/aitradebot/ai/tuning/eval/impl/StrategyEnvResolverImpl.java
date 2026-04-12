package com.chicu.aitradebot.ai.tuning.eval.impl;

import com.chicu.aitradebot.ai.tuning.eval.StrategyEnvResolver;
import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyEnvResolverImpl implements StrategyEnvResolver {

    private final StrategySettingsService strategySettingsService;

    @Override
    public Env resolve(long chatId, StrategyType type) {
        if (chatId <= 0) {
            throw new IllegalArgumentException("chatId invalid: " + chatId);
        }
        if (type == null) {
            throw new IllegalArgumentException("StrategyType is null");
        }

        // 1) active=true должно быть первым (inactive=true позже)
        Comparator<StrategySettings> byActiveFirst =
                Comparator.comparing((StrategySettings s) -> !isActiveSafe(s)); // false раньше true

        // 2) свежесть: updatedAt DESC (null last), затем id DESC (null last)
        Comparator<StrategySettings> byFreshDesc =
                Comparator.comparing(
                                StrategySettings::getUpdatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())
                        )
                        .thenComparing(
                                StrategySettings::getId,
                                Comparator.nullsLast(Comparator.reverseOrder())
                        );

        List<StrategySettings> all = strategySettingsService.findAllByChatId(chatId);

        StrategySettings s = all.stream()
                .filter(x -> x != null && x.getType() == type)
                .sorted(byActiveFirst.thenComparing(byFreshDesc))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "StrategySettings not found: chatId=" + chatId + ", type=" + type
                ));

        String ex = normalizeExchangeOrNull(s.getExchangeName());
        NetworkType net = s.getNetworkType();

        if (ex == null || net == null) {
            // ✅ тут лучше падать, чем случайно уйти в MAINNET/другую биржу
            throw new IllegalStateException(
                    "exchange/network is null/blank in StrategySettings: chatId=" + chatId
                            + ", type=" + type
                            + ", exchange=" + s.getExchangeName()
                            + ", network=" + s.getNetworkType()
                            + ", settingsId=" + s.getId()
            );
        }

        return new Env(ex, net); // Env уже нормализует + дефолтит на всякий случай
    }

    private static boolean isActiveSafe(StrategySettings s) {
        // если у тебя boolean — просто s.isActive()
        // если Boolean — защищаемся от null
        try {
            return Boolean.TRUE.equals(s.isActive());
        } catch (Exception e) {
            // на случай, если isActive() primitive boolean
            return s != null && s.isActive();
        }
    }

    private static String normalizeExchangeOrNull(String exchange) {
        if (exchange == null) return null;
        String s = exchange.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}
