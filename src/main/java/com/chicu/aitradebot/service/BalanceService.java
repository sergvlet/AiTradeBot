package com.chicu.aitradebot.service;

import com.chicu.aitradebot.domain.Balance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface BalanceService {

    /**
     * Получить все балансы пользователя
     */
    List<Balance> findByUser(Long userId);

    /**
     * Найти баланс конкретного актива пользователя
     */
    Optional<Balance> findByUserAndAsset(Long userId, String asset);

    /**
     * Обновить баланс (в double)
     */
    Balance updateBalance(Long userId, String asset, double free, double locked);

    /**
     * Обновить баланс (в BigDecimal)
     */
    Balance updateBalance(Long userId, String asset, BigDecimal free, BigDecimal locked);

    /**
     * Получить общий баланс (free + locked)
     */
    BigDecimal getTotal(Long userId, String asset);

    /**
     * Получить свободный баланс
     */
    BigDecimal getFree(Long userId, String asset);
}
