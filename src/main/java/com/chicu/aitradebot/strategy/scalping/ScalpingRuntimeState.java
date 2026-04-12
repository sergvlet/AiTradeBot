package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.StrategySettings;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

@Data
public class ScalpingRuntimeState {

    private boolean active;
    private Instant startedAt;

    private StrategySettings strategySettings;
    private ScalpingStrategySettings scalpingSettings;

    private String exchange;
    private NetworkType network;
    private String symbol;
    private String timeframe;

    private Instant lastSettingsLoadAt;
    private String settingsFingerprint;

    private final Deque<BigDecimal> closeWindow = new ArrayDeque<>();
    private final Deque<ScalpingFeatureCalculator.CandleInput> candleWindow = new ArrayDeque<>();

    private BigDecimal lastPrice;
    private ScalpingFeatureSnapshot lastFeatures;
    private ScalpingMarketRegimeSnapshot lastMarketSnapshot;
    private ScalpingMarketRegime previousRegime;
    private ScalpingSetupType lastSetupType;

    private boolean inPosition;
    private boolean longPosition;
    private BigDecimal entryPrice;
    private BigDecimal tp;
    private BigDecimal sl;
    private BigDecimal entryQty;
    private Long entryOrderId;
    private Instant entryOpenedAt;
    private Instant lastTradeClosedAt;
    private Instant lastExitAt;
    private Instant lastStopAt;
    private Instant lastIntrabarEvalAt;

    private BigDecimal breakEvenTriggerPct;
    private Integer maxHoldSec;
    private BigDecimal activeRiskScale;
    private boolean breakEvenApplied;
    private boolean partialExitDone;

    private long ticks;
    private long candles;
    private long entries;
    private long exits;
    private long warmups;
    private int consecutiveStops;

    private String lastHoldReason;
    private Instant lastHoldAt;
    private String lastRegimeLogSignature;
    private Instant lastDecisionAt;

    public void resetPositionFlags(Instant now, boolean stoppedOut) {
        inPosition = false;
        longPosition = false;
        entryPrice = null;
        tp = null;
        sl = null;
        entryQty = null;
        entryOrderId = null;
        entryOpenedAt = null;
        breakEvenTriggerPct = null;
        maxHoldSec = null;
        activeRiskScale = null;
        breakEvenApplied = false;
        partialExitDone = false;
        lastTradeClosedAt = now;
        lastExitAt = now;
        if (stoppedOut) {
            consecutiveStops++;
            lastStopAt = now;
        } else {
            consecutiveStops = 0;
        }
    }
}
