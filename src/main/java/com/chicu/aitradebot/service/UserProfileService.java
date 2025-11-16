package com.chicu.aitradebot.service;

import com.chicu.aitradebot.domain.UserProfile;

public interface UserProfileService {

    /** Возвращает текущего пользователя */
    UserProfile getCurrentUser();

    /** Возвращает chatId текущего пользователя */
    Long getCurrentChatId();

    /** Поиск пользователя по chatId */
    UserProfile findByChatId(Long chatId);

    /** Создаёт нового пользователя, если его нет */
    UserProfile getOrCreate(Long chatId, String username);
}
