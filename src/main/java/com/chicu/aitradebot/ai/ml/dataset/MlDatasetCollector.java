package com.chicu.aitradebot.ai.ml.dataset;

import com.chicu.aitradebot.ai.ml.features.MlFeatureBuilder;
import com.chicu.aitradebot.ai.ml.features.MlFeatureContext;
import com.chicu.aitradebot.strategy.core.signal.SignalType;
import com.chicu.aitradebot.trade.TradeClosedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlDatasetCollector {

    private final MlSampleRepository repo;
    private final MlFeatureBuilder featureBuilder;
    private final ObjectMapper objectMapper;

    @EventListener
    public void onTradeClosed(TradeClosedEvent e) {
        if (e == null) return;

        try {
            // label как в таблице: varchar(32)
            String label = (e.pnlPct() != null && e.pnlPct().signum() >= 0) ? "1" : "0";

            MlFeatureContext ctx = MlFeatureContext.builder()
                    .chatId(e.chatId())
                    .strategyType(e.strategyType())
                    .symbol(e.symbol())
                    .timeframe(e.timeframe())
                    .action(SignalType.BUY.name())
                    .extra(Map.of(
                            "pnlPct", e.pnlPct(),
                            "exitReason", e.exitReason()
                    ))
                    .build();

            Map<String, Object> features = featureBuilder.build(ctx);
            JsonNode featuresJson = objectMapper.valueToTree(features);

            ObjectNode meta = objectMapper.createObjectNode();
            if (e.pnlPct() != null) meta.put("pnlPct", e.pnlPct().doubleValue());
            if (e.exitReason() != null) meta.put("exitReason", String.valueOf(e.exitReason()));
            meta.put("action", "CLOSE");

            MlSampleEntity sample = MlSampleEntity.builder()
                    .chatId(e.chatId())
                    .strategyType(e.strategyType())
                    .exchange(null) // если есть в event — подставишь
                    .network(null)  // если есть в event — подставишь
                    .symbol(e.symbol())
                    .timeframe(e.timeframe())
                    .ts(Instant.now())          // если есть candleTs в event — лучше его
                    .label(label)
                    .target("win")              // опционально
                    .proba(null)                // можно сохранять pWin если есть
                    .featuresJson(featuresJson)
                    .metaJson(meta)
                    .createdAt(Instant.now())
                    .build();

            repo.save(sample);

        } catch (Exception ex) {
            log.warn("ML sample save failed: {}", ex.toString());
        }
    }
}
