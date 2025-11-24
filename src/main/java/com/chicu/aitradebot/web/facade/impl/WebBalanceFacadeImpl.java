package com.chicu.aitradebot.web.facade.impl;

import com.chicu.aitradebot.web.facade.WebBalanceFacade;
import com.chicu.aitradebot.orchestrator.AiStrategyOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WebBalanceFacadeImpl implements WebBalanceFacade {

    private final AiStrategyOrchestrator orchestrator;

    @Override
    public BalanceInfo getTotalBalance(Long chatId) {
        var b = orchestrator.getBalance(chatId);
        return new BalanceInfo(b.total(), b.free(), b.locked());
    }

    @Override
    public java.util.List<AssetBalance> getAssets(Long chatId) {
        return orchestrator.getAssets(chatId).stream()
                .map(a -> new AssetBalance(
                        a.asset(),
                        a.free(),
                        a.locked()
                ))
                .toList();
    }
}
