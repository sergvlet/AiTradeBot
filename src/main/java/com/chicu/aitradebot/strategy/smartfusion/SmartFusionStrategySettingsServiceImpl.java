// src/main/java/com/chicu/aitradebot/strategy/smartfusion/SmartFusionStrategySettingsServiceImpl.java
package com.chicu.aitradebot.strategy.smartfusion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartFusionStrategySettingsServiceImpl implements SmartFusionStrategySettingsService {

    private final SmartFusionStrategySettingsRepository repo;

    @Override
    @Transactional
    public SmartFusionStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    SmartFusionStrategySettings def = SmartFusionStrategySettings.builder()
                            .chatId(chatId)
                            // дефолты (touch() тоже выставит, но пусть будет сразу)
                            .weightTech(new BigDecimal("0.50"))
                            .weightMl(new BigDecimal("0.25"))
                            .weightRl(new BigDecimal("0.25"))
                            .decisionThreshold(new BigDecimal("0.65"))
                            .minSourceConfidence(new BigDecimal("0.55"))
                            .rsiPeriod(14)
                            .rsiBuyBelow(new BigDecimal("35"))
                            .rsiSellAbove(new BigDecimal("65"))
                            .emaFast(9)
                            .emaSlow(21)
                            .mlModelKey("default")
                            .rlAgentKey("default")
                            .lookbackCandles(300)
                            .build();

                    SmartFusionStrategySettings saved = repo.save(def);
                    log.info("🆕 Созданы настройки SMART_FUSION (chatId={})", chatId);
                    return saved;
                });
    }

    @Override
    @Transactional
    public SmartFusionStrategySettings update(Long chatId, SmartFusionStrategySettings in) {
        SmartFusionStrategySettings cur = getOrCreate(chatId);

        if (in.getWeightTech() != null) cur.setWeightTech(in.getWeightTech());
        if (in.getWeightMl() != null) cur.setWeightMl(in.getWeightMl());
        if (in.getWeightRl() != null) cur.setWeightRl(in.getWeightRl());

        if (in.getDecisionThreshold() != null) cur.setDecisionThreshold(in.getDecisionThreshold());
        if (in.getMinSourceConfidence() != null) cur.setMinSourceConfidence(in.getMinSourceConfidence());

        if (in.getRsiPeriod() != null) cur.setRsiPeriod(in.getRsiPeriod());
        if (in.getRsiBuyBelow() != null) cur.setRsiBuyBelow(in.getRsiBuyBelow());
        if (in.getRsiSellAbove() != null) cur.setRsiSellAbove(in.getRsiSellAbove());

        if (in.getEmaFast() != null) cur.setEmaFast(in.getEmaFast());
        if (in.getEmaSlow() != null) cur.setEmaSlow(in.getEmaSlow());

        if (in.getMlModelKey() != null && !in.getMlModelKey().isBlank()) cur.setMlModelKey(in.getMlModelKey().trim());
        if (in.getRlAgentKey() != null && !in.getRlAgentKey().isBlank()) cur.setRlAgentKey(in.getRlAgentKey().trim());

        if (in.getLookbackCandles() != null) cur.setLookbackCandles(in.getLookbackCandles());

        SmartFusionStrategySettings saved = repo.save(cur);
        log.info("✅ SMART_FUSION settings saved (chatId={})", chatId);
        return saved;
    }
}
