package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.ai.override.AiOverrideService;
import com.chicu.aitradebot.strategy.core.SettingsSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScalpingStrategySettingsServiceImpl
        implements ScalpingStrategySettingsService {

    private final ScalpingStrategySettingsRepository repo;
    private final AiOverrideService aiOverrideService;
    private final ObjectMapper mapper;
    // =====================================================================
    // 1) Получение или создание
    // =====================================================================
    @Override
    public ScalpingStrategySettings getOrCreate(Long chatId) {

        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    ScalpingStrategySettings def = ScalpingStrategySettings.builder()
                            .chatId(chatId)
                            .build();

                    log.info("🆕 Созданы настройки SCALPING (chatId={})", chatId);
                    return repo.save(def);
                });
    }

    // =====================================================================
    // 2) Сохранение
    // =====================================================================
    @Override
    public ScalpingStrategySettings save(ScalpingStrategySettings settings) {
        return repo.save(settings);
    }

    // =====================================================================
    // 3) Частичное обновление (ТОЛЬКО SCALPING)
    // =====================================================================
    @Override
    public ScalpingStrategySettings update(Long chatId,
                                           ScalpingStrategySettings incoming) {
        ScalpingStrategySettings s = getOrCreate(chatId);

        // windowSize
        if (incoming.getWindowSize() != null && incoming.getWindowSize() > 0) {
            s.setWindowSize(incoming.getWindowSize());
        }

        // priceChangeThreshold (%)
        if (incoming.getPriceChangeThreshold() != null
            && incoming.getPriceChangeThreshold() > 0) {
            s.setPriceChangeThreshold(incoming.getPriceChangeThreshold());
        }

        // spreadThreshold (%)
        if (incoming.getSpreadThreshold() != null
            && incoming.getSpreadThreshold() > 0) {
            s.setSpreadThreshold(incoming.getSpreadThreshold());
        }

        return repo.save(s);
    }

    // =====================================================================
    // 4) Snapshot — КАНОНИЧЕСКИЙ ВАРИАНТ
    // =====================================================================
    @Override
    public SettingsSnapshot getSnapshot(long chatId) {

        ScalpingStrategySettings s = getOrCreate(chatId);

        return SettingsSnapshot.builder()
                .chatId(chatId)
                // идентификация стратегии
                .put("strategy", "SCALPING")
                // параметры стратегии
                .put("windowSize", s.getWindowSize())
                .put("priceChangeThreshold", s.getPriceChangeThreshold())
                .put("spreadThreshold", s.getSpreadThreshold())
                .build();
    }

    @Override
    public ScalpingStrategySettings getEffective(Long chatId) {
        ScalpingStrategySettings base = getOrCreate(chatId);

        var patchOpt = aiOverrideService.getActivePatch(chatId, StrategyType.SCALPING, Instant.now());
        if (patchOpt.isEmpty() || patchOpt.get().isEmpty()) {
            return base;
        }

        Map<String, Object> patch = patchOpt.get();

        // мета поля запрещаем патчить
        patch.remove("id");
        patch.remove("chatId");
        patch.remove("createdAt");
        patch.remove("updatedAt");
        patch.remove("version");

        // base -> map -> apply patch -> map -> settings
        Map<String, Object> baseMap = mapper.convertValue(base, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        baseMap.putAll(patch);

        ScalpingStrategySettings effective = mapper.convertValue(baseMap, ScalpingStrategySettings.class);

        // гарантируем идентичность (чтобы нигде не “поплыло”)
        effective.setId(base.getId());
        effective.setChatId(base.getChatId());

        return effective;
    }
}
