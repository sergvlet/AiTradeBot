package com.chicu.aitradebot.service;

import com.chicu.aitradebot.domain.ExchangeSettings;
import java.util.Optional;

public interface ExchangeSettingsService {

    ExchangeSettings save(ExchangeSettings settings);

    Optional<ExchangeSettings> getByUserId(Long userId);
}
