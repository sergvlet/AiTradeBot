package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategyV4;
import com.chicu.aitradebot.web.dto.StrategyChartDto;
import com.chicu.aitradebot.web.facade.StrategyChartLayersProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScalpingChartLayersProvider implements StrategyChartLayersProvider {

    private final ScalpingStrategyV4 scalpingStrategy;

    @Override
    public StrategyType type() {
        return StrategyType.SCALPING;
    }

    @Override
    public StrategyChartDto.Layers buildLayers(long chatId, String symbol, String timeframe, StrategyChartDto snapshot) {

        // базово — пусто, чтобы не ломать другие слои (если появятся)
        StrategyChartDto.Layers layers = snapshot.getLayers() != null
                ? snapshot.getLayers()
                : StrategyChartDto.Layers.empty();

        ScalpingStrategyV4.WindowZoneSnapshot wz = scalpingStrategy.getLastWindowZone(chatId);
        if (wz == null || wz.high() == null || wz.low() == null) {
            return layers; // нет зоны — не рисуем
        }

        layers.setWindowZone(
                StrategyChartDto.WindowZone.builder()
                        .high(wz.high().doubleValue())
                        .low(wz.low().doubleValue())
                        .build()
        );

        return layers;
    }
}
