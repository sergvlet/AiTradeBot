package com.chicu.aitradebot.ai.override;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiOverrideService {

    private final AiOverrideRepository repo;
    private final ObjectMapper mapper;

    public Optional<Map<String, Object>> getActivePatch(Long chatId, StrategyType type, Instant now) {
        return repo.findTopByChatIdAndStrategyTypeAndActiveTrueOrderByCreatedAtDesc(chatId, type)
                .flatMap(row -> {
                    if (row.getExpiresAt() != null && now.isAfter(row.getExpiresAt())) {
                        row.setActive(false);
                        repo.save(row);
                        log.info("[AI] override expired -> deactivated (chatId={}, type={}, id={})", chatId, type, row.getId());
                        return Optional.empty();
                    }
                    try {
                        Map<String, Object> patch = mapper.readValue(
                                row.getPatchJson(),
                                new TypeReference<Map<String, Object>>() {}
                        );
                        return Optional.ofNullable(patch);
                    } catch (Exception e) {
                        log.error("[AI] override patch parse error (chatId={}, type={}, id={}): {}",
                                chatId, type, row.getId(), e.getMessage(), e);
                        return Optional.of(Collections.emptyMap());
                    }
                });
    }
}
