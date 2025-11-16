package com.chicu.aitradebot.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trade_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String strategyType;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private BigDecimal profitPct;

    @Column(nullable = false)
    private BigDecimal pnlUsd;

    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
}
