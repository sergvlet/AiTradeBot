package com.chicu.aitradebot.service.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.repository.StrategySettingsRepository;
import com.chicu.aitradebot.service.StrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Базовый сервис работы с таблицей StrategySettings.
 * Сейчас реализован минимально: без привязки к chatId,
 * просто оперирует записями по типу стратегии.
 *
 * Позже сюда можно будет добавить:
 *  - связи с пользователем / chatId
 *  - маппинг на конкретные таблицы настроек стратегий (SmartFusion, Scalping, Fibonacci и т.д.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategySettingsServiceImpl implements StrategySettingsService {

    private final StrategySettingsRepository repository;

    @Override
    public StrategySettings save(StrategySettings settings) {
        return repository.save(settings);
    }

    /**
     * ⚠️ ВРЕМЕННО:
     * chatId пока НЕ используется, так как в StrategySettingsRepository
     * нет методов findByChatId..., и сама сущность StrategySettings
     * (вероятно) пока не содержит поля chatId.
     *
     * Поэтому просто берём первую запись по типу стратегии.
     */
    @Override
    public StrategySettings getSettings(long chatId, StrategyType type) {
        List<StrategySettings> list = repository.findByType(type);
        StrategySettings result = list.isEmpty() ? null : list.get(0);

        if (result == null) {
            log.debug("⚙️ StrategySettings не найдены: type={}, chatId={}", type, chatId);
        } else {
            log.debug("⚙️ StrategySettings найдены: id={}, type={}, chatId={}",
                    result.getId(), result.getType(), chatId);
        }

        return result;
    }

    /**
     * ⚠️ ВРЕМЕННО:
     * Если настроек нет — просто логируем и возвращаем null.
     * Реальное auto-create будет сделано позже, когда будет
     * понятна структура StrategySettings (symbol, параметры и т.д.).
     */
    @Override
    public StrategySettings getOrCreate(long chatId, StrategyType type) {
        StrategySettings existing = getSettings(chatId, type);
        if (existing != null) {
            return existing;
        }

        log.warn("⚠️ getOrCreate: нет StrategySettings для type={} chatId={}, авто-создание пока не реализовано", type, chatId);
        return null;
    }
}
