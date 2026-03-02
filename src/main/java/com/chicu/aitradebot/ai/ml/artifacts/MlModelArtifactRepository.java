package com.chicu.aitradebot.ai.ml.artifacts;

import com.chicu.aitradebot.common.enums.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MlModelArtifactRepository extends JpaRepository<MlModelArtifactEntity, Long> {

    Optional<MlModelArtifactEntity> findTopByChatIdAndStrategyTypeOrderByCreatedAtDesc(Long chatId, StrategyType type);

    // ✅ ключевое: последний артефакт именно под (symbol,timeframe)
    Optional<MlModelArtifactEntity> findTopByChatIdAndStrategyTypeAndSymbolAndTimeframeOrderByCreatedAtDesc(
            Long chatId,
            StrategyType type,
            String symbol,
            String timeframe
    );

    // ✅ если нужно “поднять модель по ключу”
    Optional<MlModelArtifactEntity> findTopByModelKeyOrderByCreatedAtDesc(String modelKey);

    default MlModelArtifactEntity findLatest(Long chatId, StrategyType type) {
        return findTopByChatIdAndStrategyTypeOrderByCreatedAtDesc(chatId, type).orElse(null);
    }

    default MlModelArtifactEntity findLatest(Long chatId, StrategyType type, String symbol, String timeframe) {
        if (symbol == null || symbol.isBlank()) return findLatest(chatId, type);
        if (timeframe == null || timeframe.isBlank()) return findLatest(chatId, type);
        return findTopByChatIdAndStrategyTypeAndSymbolAndTimeframeOrderByCreatedAtDesc(
                chatId, type, symbol.trim().toUpperCase(),
                timeframe.trim().toLowerCase()
        ).orElse(null);
    }
}