package com.chicu.aitradebot.service;

import com.chicu.aitradebot.domain.UserStrategy;
import com.chicu.aitradebot.domain.UserProfile;
import com.chicu.aitradebot.domain.StrategySettings;
import java.util.List;

public interface UserStrategyService {

    UserStrategy linkUserToStrategy(UserProfile user, StrategySettings strategy);

    List<UserStrategy> findByUser(UserProfile user);

    List<UserStrategy> findActive();

    void deactivate(Long id);
}
