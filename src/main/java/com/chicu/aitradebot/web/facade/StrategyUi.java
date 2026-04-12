package com.chicu.aitradebot.web.facade;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyUi {

    private Long chatId;
    private StrategyType type;

    private String title;
    private String description;

    private boolean active;

    private String exchangeName;
    private NetworkType networkType;
    private String symbol;
    private String timeframe;

    private AdvancedControlMode advancedControlMode;
    private String runPhase;

    private boolean autoTuneEnabled;
    private boolean mlGateEnabled;

    private BigDecimal mlConfidence;
    private BigDecimal totalProfitPct;

    private Instant startedAt;
    private Instant stoppedAt;

    private Integer version;

    public static StrategyUi empty(Long chatId,
                                   StrategyType type,
                                   String exchange,
                                   NetworkType network) {
        return StrategyUi.builder()
                .chatId(chatId)
                .type(type)
                .title(resolveTitle(type))
                .description(resolveDescription(type))
                .active(false)
                .exchangeName(normalizeExchange(exchange))
                .networkType(network != null ? network : NetworkType.MAINNET)
                .symbol(null)
                .timeframe(null)
                .advancedControlMode(AdvancedControlMode.MANUAL)
                .runPhase("—")
                .autoTuneEnabled(false)
                .mlGateEnabled(false)
                .mlConfidence(BigDecimal.ZERO)
                .totalProfitPct(BigDecimal.ZERO)
                .startedAt(null)
                .stoppedAt(null)
                .version(null)
                .build();
    }

    public static StrategyUi fromSettings(StrategySettings s) {
        if (s == null) {
            return StrategyUi.builder()
                    .title(resolveTitle(null))
                    .description(resolveDescription(null))
                    .active(false)
                    .exchangeName("BINANCE")
                    .networkType(NetworkType.MAINNET)
                    .advancedControlMode(AdvancedControlMode.MANUAL)
                    .runPhase("—")
                    .autoTuneEnabled(false)
                    .mlGateEnabled(false)
                    .mlConfidence(BigDecimal.ZERO)
                    .totalProfitPct(BigDecimal.ZERO)
                    .build();
        }

        return StrategyUi.builder()
                .chatId(s.getChatId())
                .type(s.getType())
                .title(resolveTitle(s.getType()))
                .description(resolveDescription(s.getType()))
                .active(s.isActive())
                .exchangeName(normalizeExchange(s.getExchangeName()))
                .networkType(s.getNetworkType() != null ? s.getNetworkType() : NetworkType.MAINNET)
                .symbol(normalizeSymbol(s.getSymbol()))
                .timeframe(normalizeTimeframe(s.getTimeframe()))
                .advancedControlMode(s.getAdvancedControlMode() != null ? s.getAdvancedControlMode() : AdvancedControlMode.MANUAL)
                .runPhase(s.getRunPhase() != null && !s.getRunPhase().isBlank()
                        ? s.getRunPhase().trim().toUpperCase(Locale.ROOT)
                        : "—")
                .autoTuneEnabled(s.isAutoTuneEnabled())
                .mlGateEnabled(s.isMlGateEnabled())
                .mlConfidence(s.getMlConfidence() != null ? s.getMlConfidence() : BigDecimal.ZERO)
                .totalProfitPct(s.getTotalProfitPct() != null ? s.getTotalProfitPct() : BigDecimal.ZERO)
                .startedAt(toInstant(s.getStartedAt()))
                .stoppedAt(toInstant(s.getStoppedAt()))
                .version(s.getVersion())
                .build();
    }

    public StrategyUi withActive(boolean active) {
        return StrategyUi.builder()
                .chatId(this.chatId)
                .type(this.type)
                .title(this.title)
                .description(this.description)
                .active(active)
                .exchangeName(this.exchangeName)
                .networkType(this.networkType)
                .symbol(this.symbol)
                .timeframe(this.timeframe)
                .advancedControlMode(this.advancedControlMode)
                .runPhase(this.runPhase)
                .autoTuneEnabled(this.autoTuneEnabled)
                .mlGateEnabled(this.mlGateEnabled)
                .mlConfidence(this.mlConfidence)
                .totalProfitPct(this.totalProfitPct)
                .startedAt(this.startedAt)
                .stoppedAt(this.stoppedAt)
                .version(this.version)
                .build();
    }

    private static Instant toInstant(LocalDateTime value) {
        return value != null ? value.atZone(ZoneId.systemDefault()).toInstant() : null;
    }

    private static String normalizeExchange(String exchange) {
        if (exchange == null || exchange.isBlank()) {
            return "BINANCE";
        }
        return exchange.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeTimeframe(String timeframe) {
        if (timeframe == null || timeframe.isBlank()) {
            return null;
        }
        return timeframe.trim().toLowerCase(Locale.ROOT);
    }

    private static String resolveTitle(StrategyType type) {
        if (type == null) return "UNKNOWN";

        return switch (type) {
            case WINDOW_SCALPING -> "Window Scalping";
            case EMA_CROSSOVER -> "EMA Crossover";
            case SCALPING -> "Scalping";
            case FIBONACCI_GRID -> "Fibonacci Grid";
            case GRID -> "Grid";
            case RSI_OBOS -> "RSI OB/OS";
            case TREND_FOLLOWING -> "Trend Following";
            case VOLATILITY_BREAKOUT -> "Volatility Breakout";
            case SUPPORT_RESISTANCE -> "Support / Resistance";
            case PRICE_ACTION -> "Price Action";
            case MOMENTUM -> "Momentum";
            case TREND -> "Trend";
            case BREAKOUT -> "Breakout";
            case DCA -> "DCA";
            case VWAP -> "VWAP";
            case ORDER_FLOW -> "Order Flow";
            case ML_CLASSIFICATION -> "ML Classification";
            case RL_AGENT -> "RL Agent";
            case HYBRID -> "Hybrid";
            case SMART_FUSION -> "Smart Fusion";
            case FIBONACCI_RETRACE -> "Fibonacci Retrace";
            case MEAN_REVERSION -> "Mean Reversion";
            case VOLUME_PROFILE -> "Volume Profile";
            case GLOBAL -> "Global";
        };
    }

    private static String resolveDescription(StrategyType type) {
        if (type == null) {
            return "Описание стратегии пока не задано";
        }

        return switch (type) {
            case WINDOW_SCALPING -> "Короткие сделки внутри торгового окна с быстрым сопровождением.";
            case EMA_CROSSOVER -> "Входы по пересечению быстрых и медленных EMA.";
            case SCALPING -> "Короткие сделки на малых движениях цены.";
            case FIBONACCI_GRID -> "Сетка ордеров по уровням Фибоначчи.";
            case GRID -> "Сеточная торговля в диапазоне.";
            case RSI_OBOS -> "Сигналы перекупленности и перепроданности по RSI.";
            case TREND_FOLLOWING -> "Торговля по направлению основного тренда.";
            case VOLATILITY_BREAKOUT -> "Входы на пробой после расширения волатильности.";
            case SUPPORT_RESISTANCE -> "Работа от уровней поддержки и сопротивления.";
            case PRICE_ACTION -> "Решения по структуре свечей и поведению цены.";
            case MOMENTUM -> "Поиск ускорения движения цены.";
            case TREND -> "Базовая трендовая стратегия.";
            case BREAKOUT -> "Торговля пробоев уровней и диапазонов.";
            case DCA -> "Пошаговый набор позиции по стратегии усреднения.";
            case VWAP -> "Ориентация на VWAP как базовый уровень цены.";
            case ORDER_FLOW -> "Оценка рыночного потока покупателей и продавцов.";
            case ML_CLASSIFICATION -> "Сигналы на основе ML-классификации.";
            case RL_AGENT -> "Стратегия с reinforcement learning.";
            case HYBRID -> "Комбинация классических правил и ML-фильтра.";
            case SMART_FUSION -> "Объединение нескольких источников сигнала.";
            case FIBONACCI_RETRACE -> "Входы на откатах по уровням Фибоначчи.";
            case MEAN_REVERSION -> "Возврат цены к среднему значению.";
            case VOLUME_PROFILE -> "Опора на распределение объёма по уровням.";
            case GLOBAL -> "Системная стратегия общего назначения.";
        };
    }
}