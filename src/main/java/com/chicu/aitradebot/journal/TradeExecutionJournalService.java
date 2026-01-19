package com.chicu.aitradebot.journal;

import com.chicu.aitradebot.common.enums.NetworkType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TradeExecutionJournalService {

    private final TradeExecutionEventRepository repo;

    /**
     * Дедуп по eventUid (важно для WS/userStream).
     */
    @Transactional
    public TradeExecutionEvent saveDedup(TradeExecutionEvent e) {
        Objects.requireNonNull(e, "event is null");
        if (e.getEventUid() == null || e.getEventUid().isBlank()) {
            throw new IllegalArgumentException("eventUid is required for dedup");
        }

        return repo.findByEventUid(e.getEventUid())
                .orElseGet(() -> repo.save(e));
    }

    /**
     * Удобный метод: когда networkType у тебя Enum, а в entity хранится String.
     */
    @Transactional
    public TradeExecutionEvent saveDedup(TradeExecutionEvent e, NetworkType networkType) {
        if (networkType != null) {
            e.setNetworkType(NetworkType.valueOf(networkType.name()));
        }
        return saveDedup(e);
    }
}
