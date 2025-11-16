package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.domain.UserProfile;
import com.chicu.aitradebot.repository.UserProfileRepository;
import com.chicu.aitradebot.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileServiceImpl implements UserProfileService {

    private final UserProfileRepository repository;

    @Override
    public UserProfile getCurrentUser() {
        // временно возвращаем первого пользователя
        return repository.findAll().stream()
                .findFirst()
                .orElseGet(() -> getOrCreate(1L, "default_user"));
    }

    @Override
    public Long getCurrentChatId() {
        return getCurrentUser().getChatId();
    }

    @Override
    public UserProfile findByChatId(Long chatId) {
        return repository.findByChatId(chatId).orElse(null);
    }

    @Override
    @Transactional
    public UserProfile getOrCreate(Long chatId, String username) {
        return repository.findByChatId(chatId)
                .orElseGet(() -> {
                    UserProfile user = UserProfile.builder()
                            .chatId(chatId)
                            .username(username)
                            .balanceUsd(BigDecimal.valueOf(100))
                            .active(true)
                            .build();
                    repository.save(user);
                    log.info("🆕 Создан новый пользователь chatId={}", chatId);
                    return user;
                });
    }
}
