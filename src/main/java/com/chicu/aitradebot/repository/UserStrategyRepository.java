package com.chicu.aitradebot.repository;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.UserStrategy;
import com.chicu.aitradebot.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserStrategyRepository extends JpaRepository<UserStrategy, Long> {

    /**
     * Все стратегии данного пользователя.
     */
    List<UserStrategy> findByUser(UserProfile user);

    /**
     * Одна стратегия по пользователю и типу стратегии.
     * Поле "type" берётся из вложенной сущности strategySettings.type.
     */
    Optional<UserStrategy> findByUserAndStrategySettings_Type(UserProfile user, StrategyType type);


    List<UserStrategy> findByActiveTrue();



}
