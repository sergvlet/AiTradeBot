package com.chicu.aitradebot.orchestrator.dto;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import lombok.*;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class StrategyRunInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // === Основные данные ===
    private Long id;
    private Long chatId;
    private StrategyType type;
    private String symbol;
    private boolean active;

    public boolean isRunning() {
        return active;
    }

    // === Временные отметки ===
    private Instant startedAt;
    private Instant stoppedAt;
    private Instant lastUpdate;
    private Instant lastTickAt;
    private String lastEvent;
    private String threadName;

    // === Финансовые показатели ===
    private BigDecimal balanceUsd;
    private BigDecimal totalProfitPct;
    private long totalTrades;

    private BigDecimal initialEquity;
    private BigDecimal currentEquity;
    private BigDecimal pnlUsd;
    private BigDecimal winRatePct;

    // === AI слой ===
    private BigDecimal mlConfidence;   // ✔ ИЗ-ЗА ЭТОГО БЫЛ КРАШ

    // === Настройки стратегии ===
    private Integer version;
    private String timeframe;
    private BigDecimal tpPct;
    private BigDecimal slPct;
    private BigDecimal riskPerTradePct;

    // === Алиасы ===
    public BigDecimal getPnlPct() { return totalProfitPct; }
    public void setPnlPct(BigDecimal v) { this.totalProfitPct = v; }

    // === График ===
    private List<String> timestamps;
    private List<Double> prices;
    private List<Integer> buyIndexes;
    private List<Integer> sellIndexes;

    // === Параметры среды ===
    private String exchange;
    private NetworkType networkType;
}
