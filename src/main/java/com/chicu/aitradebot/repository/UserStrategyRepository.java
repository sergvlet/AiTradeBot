package com.chicu.aitradebot.repository;

import com.chicu.aitradebot.domain.UserStrategy;
import com.chicu.aitradebot.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserStrategyRepository extends JpaRepository<UserStrategy, Long> {

    List<UserStrategy> findByUser(UserProfile user);

    List<UserStrategy> findByUserChatId(Long chatId);

    List<UserStrategy> findByActiveTrue();

    long countByUserChatId(Long chatId);
}
