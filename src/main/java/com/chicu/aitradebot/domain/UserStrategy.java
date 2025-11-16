package com.chicu.aitradebot.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_strategies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"user", "strategySettings"})
public class UserStrategy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private UserProfile user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "strategy_id")
    private StrategySettings strategySettings;

    @Builder.Default
    private boolean active = false;

    private LocalDateTime startedAt;
    private LocalDateTime stoppedAt;

    @Builder.Default
    private long totalTrades = 0L;

    @Builder.Default
    @Column(nullable = false, precision = 12, scale = 6)
    private BigDecimal totalProfitPct = BigDecimal.ZERO; // накопленный PnL %

    @Builder.Default
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal mlConfidence = BigDecimal.ZERO;   // 0..1
}
