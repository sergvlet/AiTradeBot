package com.chicu.aitradebot.web.facade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;

import java.math.BigDecimal;
import java.util.List;

public interface WebStrategyFacade {

    // ================================================================
    // 📋 СПИСОК СТРАТЕГИЙ (UI / Dashboard)
    // ================================================================
    List<StrategyUi> getStrategies(
            Long chatId,
            String exchange,
            NetworkType network
    );

    // ================================================================
    // 🔁 TOGGLE
    // ЕДИНСТВЕННАЯ точка управления из UI / API
    // ================================================================
    StrategyRunInfo toggle(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );

    // ================================================================
    // ℹ STATUS
    // ================================================================
    StrategyRunInfo getRunInfo(
            Long chatId,
            StrategyType type,
            String exchange,
            NetworkType network
    );

    // ================================================================
    // 📒 ЖУРНАЛ ОРДЕРОВ / СДЕЛОК
    // PnL отдается уже в главном активе пары (quote asset, например USDT).
    // ================================================================
    default List<OrderView> listOrders(Long chatId, String symbol) {
        return listOrders(chatId, null, symbol, null, null);
    }

    List<OrderView> listOrders(
            Long chatId,
            StrategyType type,
            String symbol,
            String exchange,
            NetworkType network
    );

    record OrderResult(boolean success, String message, Long orderId) {
    }

    record OrderView(
            Long id,
            String symbol,
            String side,
            String status,
            BigDecimal price,
            BigDecimal quantity,
            Boolean filled,
            Long timestamp,
            BigDecimal total,
            BigDecimal realizedPnl,
            BigDecimal realizedPnlPct,
            String pnlAsset,
            Long matchedEntryId
    ) {
    }
}
