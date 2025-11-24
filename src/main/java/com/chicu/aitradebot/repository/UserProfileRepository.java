package com.chicu.aitradebot.repository;

import com.chicu.aitradebot.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {



    /** Поиск пользователя по chatId */
    Optional<UserProfile> findByChatId(Long chatId);
}
