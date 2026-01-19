package com.chicu.aitradebot.strategy.windowscalping;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;



@Slf4j
@Service
@RequiredArgsConstructor
public class WindowScalpingStrategySettingsServiceImpl implements WindowScalpingStrategySettingsService {

    private final WindowScalpingStrategySettingsRepository repo;

    @Override
    public WindowScalpingStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    WindowScalpingStrategySettings def = WindowScalpingStrategySettings.builder()
                            .chatId(chatId)
                            .build();
                    log.info("🆕 Созданы настройки WINDOW_SCALPING (chatId={})", chatId);
                    return repo.save(def);
                });
    }

    @Override
    public WindowScalpingStrategySettings update(Long chatId, WindowScalpingStrategySettings incoming) {

        WindowScalpingStrategySettings cur = getOrCreate(chatId);

        // ✅ TP/SL
        if (incoming.getTakeProfitPct() != null && incoming.getTakeProfitPct().signum() > 0) {
            cur.setTakeProfitPct(incoming.getTakeProfitPct());
        }
        if (incoming.getStopLossPct() != null && incoming.getStopLossPct().signum() > 0) {
            cur.setStopLossPct(incoming.getStopLossPct());
        }

        // ✅ WINDOW поля
        if (incoming.getWindowSize() != null && incoming.getWindowSize() >= 5) {
            cur.setWindowSize(incoming.getWindowSize());
        }
        if (incoming.getEntryFromLowPct() != null && incoming.getEntryFromLowPct() >= 0) {
            cur.setEntryFromLowPct(incoming.getEntryFromLowPct());
        }
        if (incoming.getEntryFromHighPct() != null && incoming.getEntryFromHighPct() >= 0) {
            cur.setEntryFromHighPct(incoming.getEntryFromHighPct());
        }
        if (incoming.getMinRangePct() != null && incoming.getMinRangePct() >= 0) {
            cur.setMinRangePct(incoming.getMinRangePct());
        }
        if (incoming.getMaxSpreadPct() != null && incoming.getMaxSpreadPct() >= 0) {
            cur.setMaxSpreadPct(incoming.getMaxSpreadPct());
        }

        WindowScalpingStrategySettings saved = repo.save(cur);
        log.info("✅ WINDOW_SCALPING settings updated (chatId={}, tpPct={}, slPct={}, windowSize={})",
                chatId,
                saved.getTakeProfitPct(),
                saved.getStopLossPct(),
                saved.getWindowSize()
        );
        return saved;
    }
}
