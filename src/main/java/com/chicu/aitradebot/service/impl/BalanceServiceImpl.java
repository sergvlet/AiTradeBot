package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.domain.Balance;
import com.chicu.aitradebot.repository.BalanceRepository;
import com.chicu.aitradebot.service.BalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class BalanceServiceImpl implements BalanceService {

    private final BalanceRepository balanceRepository;

    @Override
    @Transactional(readOnly = true)
    public List<Balance> findByUser(Long userId) {
        if (userId == null) throw new IllegalArgumentException("userId cannot be null");
        return balanceRepository.findAllByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Balance> findByUserAndAsset(Long userId, String asset) {
        if (userId == null || asset == null) return Optional.empty();
        String normalized = asset.toUpperCase(Locale.ROOT).trim();
        return balanceRepository.findByUserIdAndAsset(userId, normalized);
    }

    @Override
    public Balance updateBalance(Long userId, String asset, double free, double locked) {
        return updateBalance(userId, asset, BigDecimal.valueOf(free), BigDecimal.valueOf(locked));
    }

    @Override
    public Balance updateBalance(Long userId, String asset, BigDecimal free, BigDecimal locked) {
        if (userId == null) throw new IllegalArgumentException("userId cannot be null");
        if (asset == null) throw new IllegalArgumentException("asset cannot be null");

        String normalizedAsset = asset.toUpperCase(Locale.ROOT).trim();

        Balance balance = balanceRepository.findByUserIdAndAsset(userId, normalizedAsset)
                .orElseGet(() -> {
                    log.info("🪙 Создаётся новый баланс для userId={} asset={}", userId, normalizedAsset);
                    return Balance.builder()
                            .userId(userId)
                            .asset(normalizedAsset)
                            .free(BigDecimal.ZERO)
                            .locked(BigDecimal.ZERO)
                            .build();
                });

        balance.setFree(free != null ? free : BigDecimal.ZERO);
        balance.setLocked(locked != null ? locked : BigDecimal.ZERO);

        Balance saved = balanceRepository.save(balance);
        log.debug("💾 Обновлён баланс {}: free={}, locked={}, total={}",
                normalizedAsset, saved.getFree(), saved.getLocked(), saved.getTotal());

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getTotal(Long userId, String asset) {
        if (userId == null || asset == null) return BigDecimal.ZERO;
        return findByUserAndAsset(userId, asset)
                .map(Balance::getTotal)
                .orElse(BigDecimal.ZERO);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getFree(Long userId, String asset) {
        if (userId == null || asset == null) return BigDecimal.ZERO;
        return findByUserAndAsset(userId, asset)
                .map(Balance::getFree)
                .orElse(BigDecimal.ZERO);
    }
}
