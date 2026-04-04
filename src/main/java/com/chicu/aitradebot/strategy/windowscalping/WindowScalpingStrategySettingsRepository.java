package com.chicu.aitradebot.strategy.windowscalping;

import com.chicu.aitradebot.common.enums.NetworkType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WindowScalpingStrategySettingsRepository
        extends JpaRepository<WindowScalpingStrategySettings, Long> {

    Optional<WindowScalpingStrategySettings> findTopByChatIdOrderByUpdatedAtDesc(Long chatId);

    Optional<WindowScalpingStrategySettings> findByIdAndChatId(Long id, Long chatId);

    Optional<WindowScalpingStrategySettings> findByChatIdAndExchangeNameAndNetworkTypeAndSymbolAndTimeframe(
            Long chatId,
            String exchangeName,
            NetworkType networkType,
            String symbol,
            String timeframe
    );

    @Query("""
            select s.version
            from WindowScalpingStrategySettings s
            where s.chatId = :chatId
              and upper(s.exchangeName) = upper(:exchangeName)
              and s.networkType = :networkType
              and upper(s.symbol) = upper(:symbol)
              and lower(s.timeframe) = lower(:timeframe)
            """)
    Integer findVersionByContext(@Param("chatId") Long chatId,
                                 @Param("exchangeName") String exchangeName,
                                 @Param("networkType") NetworkType networkType,
                                 @Param("symbol") String symbol,
                                 @Param("timeframe") String timeframe);
}