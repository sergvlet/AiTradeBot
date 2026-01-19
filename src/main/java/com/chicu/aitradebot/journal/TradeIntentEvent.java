package com.chicu.aitradebot.journal;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "trade_intent_event",
        indexes = {
                @Index(name = "ix_intent_chat_type_ex_net", columnList = "chat_id,strategy_type,exchange_name,network_type"),
                @Index(name = "ix_intent_symbol_tf", columnList = "symbol,timeframe"),
                @Index(name = "ix_intent_created_at", columnList = "created_at"),
                @Index(name = "ux_intent_correlation", columnList = "correlation_id", unique = true),
                @Index(name = "ix_intent_client_order", columnList = "client_order_id")
        }
)
public class TradeIntentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ==========================
    // ИДЕНТИФИКАЦИЯ СТРАТЕГИИ
    // ==========================
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 64)
    private StrategyType strategyType;

    /**
     * Название биржи строкой (BINANCE/BYBIT/OKX...) — чтобы не зависеть от enum, если у тебя они расширяются.
     */
    @Column(name = "exchange_name", nullable = false, length = 32)
    private String exchangeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "network_type", nullable = false, length = 16)
    private NetworkType networkType;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Column(name = "timeframe", nullable = false, length = 16)
    private String timeframe;

    // ==========================
    // КОРРЕЛЯЦИЯ (СКЛЕЙКА С БИРЖЕЙ)
    // ==========================
    /**
     * Уникальный ID сделки/попытки (UUID строкой).
     * Этот ID должен жить в clientOrderId (или быть частью clientOrderId), чтобы склеивать с фактами с биржи.
     */
    @Column(name = "correlation_id", nullable = false, length = 64, unique = true)
    private String correlationId;

    /**
     * clientOrderId, который реально отправили на биржу (если дошли до размещения).
     * Может быть null, если AI/риск заблокировали до ордера.
     */
    @Column(name = "client_order_id", length = 64)
    private String clientOrderId;

    // ==========================
    // СИГНАЛ / РЕШЕНИЕ
    // ==========================
    @Enumerated(EnumType.STRING)
    @Column(name = "signal", nullable = false, length = 16)
    private Signal signal;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 16)
    private Decision decision;

    /**
     * Причина решения: короткий код (например: RISK_DAILY_LIMIT, AI_LOW_EDGE, SPREAD_HIGH, COOLDOWN, OK).
     */
    @Column(name = "reason_code", length = 48)
    private String reasonCode;

    // ==========================
    // AI-ОЦЕНКИ (опционально)
    // ==========================
    @Column(name = "confidence", precision = 10, scale = 6)
    private BigDecimal confidence;

    @Column(name = "expected_return", precision = 16, scale = 8)
    private BigDecimal expectedReturn;

    @Column(name = "uncertainty", precision = 10, scale = 6)
    private BigDecimal uncertainty;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    // ==========================
    // СНИМКИ / ПОЛЕЗНАЯ ДИАГНОСТИКА
    // ==========================
    /**
     * JSON со снимком effective-настроек (baseline + overrides после резолва).
     * Нужен для обучения/дебага. Храним строкой, чтобы не тащить отдельные таблицы.
     */
    @Lob
    @Column(name = "effective_settings_json")
    private String effectiveSettingsJson;

    /**
     * JSON с фичами/контекстом на момент сигнала.
     * В проде обычно лучше хранить ссылку на feature-store, но на старте можно хранить прямо тут.
     */
    @Lob
    @Column(name = "features_json")
    private String featuresJson;

    // ==========================
    // ВРЕМЯ
    // ==========================
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        // нормализация
        if (symbol != null) symbol = symbol.trim().toUpperCase();
        if (timeframe != null) timeframe = timeframe.trim();
        if (exchangeName != null) exchangeName = exchangeName.trim().toUpperCase();
        if (correlationId != null) correlationId = correlationId.trim();
        if (clientOrderId != null) clientOrderId = clientOrderId.trim();
        if (reasonCode != null) reasonCode = reasonCode.trim().toUpperCase();
        if (modelVersion != null) modelVersion = modelVersion.trim();
    }

    public enum Signal {
        BUY, SELL, HOLD
    }

    public enum Decision {
        /**
         * Разрешили перейти к исполнению (но ордер мог не дойти из-за ошибок/лимитов биржи).
         */
        ALLOW,
        /**
         * Запретили вход (AI/риск/гварды).
         */
        REJECT,
        /**
         * “Не уверен” / пропуск торговли (абстейн).
         */
        ABSTAIN
    }
}
