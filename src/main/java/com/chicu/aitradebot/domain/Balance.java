package com.chicu.aitradebot.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(
    name = "balances",
    uniqueConstraints = @UniqueConstraint(name = "uk_balance_user_asset", columnNames = {"user_id","asset"}),
    indexes = {
        @Index(name = "ix_balance_user", columnList = "user_id"),
        @Index(name = "ix_balance_user_asset", columnList = "user_id,asset")
    }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Balance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 24)
    private String asset; // BTC, USDT, ETH и т.д.

    // Денежная точность (до 18 знаков, 8 после запятой)
    @Column(nullable = false, precision = 28, scale = 10)
    @Builder.Default
    private BigDecimal free = BigDecimal.ZERO;

    @Column(nullable = false, precision = 28, scale = 10)
    @Builder.Default
    private BigDecimal locked = BigDecimal.ZERO;

    @PrePersist
    @PreUpdate
    void nullGuards() {
        if (free == null) free = BigDecimal.ZERO;
        if (locked == null) locked = BigDecimal.ZERO;
        if (asset != null) asset = asset.trim().toUpperCase();
    }

    @Transient
    public BigDecimal getTotal() {
        // NPE-safe
        return (free == null ? BigDecimal.ZERO : free)
                .add(locked == null ? BigDecimal.ZERO : locked);
    }

    // Бизнес-эквивалентность по ключу (userId + asset)
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Balance b)) return false;
        return Objects.equals(userId, b.userId) &&
               Objects.equals(asset, b.asset);
    }
    @Override
    public int hashCode() {
        return Objects.hash(userId, asset);
    }
}
