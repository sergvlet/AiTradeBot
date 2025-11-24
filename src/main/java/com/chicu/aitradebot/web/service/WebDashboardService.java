package com.chicu.aitradebot.web.service;

import com.chicu.aitradebot.domain.UserStrategy;
import com.chicu.aitradebot.repository.UserStrategyRepository;
import com.chicu.aitradebot.web.model.DashboardStats;
import com.chicu.aitradebot.web.model.UserProfileView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebDashboardService {

    private final UserStrategyRepository userStrategyRepository;

    /** Основная статистика для дашборда — напрямую из БД. */
    public DashboardStats getDashboardStats() {
        var all = userStrategyRepository.findAll();

        long active = all.stream().filter(UserStrategy::isActive).count();
        long total  = all.size();

        // TODO: заменить демо-метрики на реальные агрегаты из БД (PnL/Confidence/Users)
        return DashboardStats.builder()
                .activeStrategies(active)
                .totalStrategies(total)
                .totalProfit(12.3)
                .avgConfidence(0.84)
                .usersCount(128)
                .build();
    }

    /** Профиль пользователя (заглушка до подключения UserProfileRepository). */
    public UserProfileView getUserProfile() {
        return UserProfileView.builder()
                .username("Demo User")
                .chatId(10001L)
                .exchange("Binance Mainnet")
                .email("demo@aitrade.io")
                .build();
    }
    /**
     * Временная заглушка для совместимости со старым кодом графиков.
     * Позже будет заменено на полноценный ChartFacade.
     */
    public Object build(long chatId, String symbol, int limit, String timeframe) {
        // TODO: заменить на ChartFacade v4
        return Map.of(
                "chatId", chatId,
                "symbol", symbol,
                "limit", limit,
                "timeframe", timeframe
        );
    }

}
