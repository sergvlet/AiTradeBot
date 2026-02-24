package com.chicu.aitradebot.strategy.windowscalping;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WindowScalpingStrategySettingsRepository
        extends JpaRepository<WindowScalpingStrategySettings, Long> {

    Optional<WindowScalpingStrategySettings> findByChatId(Long chatId);

    @Query("select s.version from WindowScalpingStrategySettings s where s.chatId = :chatId")
    Integer findVersionByChatId(@Param("chatId") Long chatId);
}
