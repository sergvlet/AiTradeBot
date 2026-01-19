package com.chicu.aitradebot.strategy.dca;

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
@Table(name = "dca_strategy_settings",
        indexes = {
                @Index(name = "idx_dca_chat_id", columnList = "chat_id")
        })
public class DcaStrategySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    /**
     * Интервал покупок в минутах (например 60 = раз в час)
     */
    @Column(name = "interval_minutes")
    private Integer intervalMinutes;

    /**
     * Объём заявки (в валюте котировки, чаще USDT), например 10 USDT.
     * Конкретную интерпретацию объёма решает TradeExecutionService.
     */
    @Column(name = "order_volume", precision = 38, scale = 18)
    private BigDecimal orderVolume;

    /**
     * Опционально — если хочешь хранить TP/SL именно в DCA-настройках.
     * Если null — TradeExecutionService может брать из StrategySettings/дефолтов.
     */
    @Column(name = "take_profit_pct", precision = 38, scale = 18)
    private BigDecimal takeProfitPct;

    @Column(name = "stop_loss_pct", precision = 38, scale = 18)
    private BigDecimal stopLossPct;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Integer version;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
