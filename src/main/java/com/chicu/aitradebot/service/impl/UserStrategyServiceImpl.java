package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.domain.UserStrategy;
import com.chicu.aitradebot.domain.UserProfile;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.repository.UserStrategyRepository;
import com.chicu.aitradebot.service.UserStrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserStrategyServiceImpl implements UserStrategyService {

    private final UserStrategyRepository repository;

    @Override
    public UserStrategy linkUserToStrategy(UserProfile user, StrategySettings strategy) {
        UserStrategy us = UserStrategy.builder()
                .user(user)
                .strategySettings(strategy)
                .active(true)
                .startedAt(LocalDateTime.now())
                .totalTrades(0L)
                .totalProfitPct(BigDecimal.ZERO)
                .mlConfidence(BigDecimal.ZERO)
                .build();
        return repository.save(us);
    }

    @Override
    public List<UserStrategy> findByUser(UserProfile user) {
        return repository.findByUser(user);
    }

    @Override
    public List<UserStrategy> findActive() {
        return repository.findByActiveTrue();
    }

    @Override
    public void deactivate(Long id) {
        repository.findById(id).ifPresent(us -> {
            us.setActive(false);
            us.setStoppedAt(LocalDateTime.now());
            repository.save(us);
        });
    }
}
