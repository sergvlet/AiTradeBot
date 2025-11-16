package com.chicu.aitradebot.domain;

import com.chicu.aitradebot.common.enums.NetworkType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Пользователь Telegram. Один UserProfile может иметь множество стратегий.
 */
@Entity
@Table(name = "user_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfile {

    @Id
    @Column(name = "chat_id", nullable = false, updatable = false)
    private Long chatId; // Telegram chat ID

    @Column(nullable = false, length = 64)
    private String username;

    @Column(length = 64)
    private String firstName;

    @Column(length = 64)
    private String lastName;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NetworkType networkType = NetworkType.TESTNET;

    @Column(precision = 12, scale = 2)
    private BigDecimal balanceUsd;


    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(nullable = false, length = 8)
    private String locale = "ru";

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime registeredAt = LocalDateTime.now();

    @Column
    private LocalDateTime lastActiveAt;

    @PrePersist
    public void onCreate() {
        if (registeredAt == null) registeredAt = LocalDateTime.now();
        // Если вдруг кто-то явно установил active=false до сохранения — оставляем как есть.
        // Ничего не меняем здесь.
    }

    @PreUpdate
    public void onUpdate() {
        lastActiveAt = LocalDateTime.now();
    }
}
