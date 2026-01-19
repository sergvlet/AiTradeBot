package com.chicu.aitradebot.domain;

import com.chicu.aitradebot.common.enums.NetworkType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "exchange_settings",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "ux_exchange_settings_chat_exchange_network",
                        columnNames = {"chat_id", "exchange", "network"}
                )
        },
        indexes = {
                @Index(name = "ix_exchange_settings_chat", columnList = "chat_id"),
                @Index(name = "ix_exchange_settings_exchange", columnList = "exchange")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // =====================================================
    // ИДЕНТИФИКАЦИЯ
    // =====================================================

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "exchange", nullable = false, length = 32)
    private String exchange;

    @Enumerated(EnumType.STRING)
    @Column(name = "network", nullable = false, length = 16)
    private NetworkType network;

    // =====================================================
    // КЛЮЧИ / ДОСТУП (могут быть null, пока юзер не ввёл)
    // =====================================================

    @Column(name = "api_key", length = 256)
    private String apiKey;

    @Column(name = "api_secret", length = 256)
    private String apiSecret;

    @Column(name = "passphrase", length = 256)
    private String passphrase;

    @Column(name = "sub_account", length = 128)
    private String subAccount;

    // =====================================================
    // JPA HOOKS
    // =====================================================

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (exchange != null) exchange = normalizeUpper(exchange);

        // ✅ подчистим пробелы, чтобы "   " не считалось ключом
        apiKey = normalizeNullable(apiKey);
        apiSecret = normalizeNullable(apiSecret);
        passphrase = normalizeNullable(passphrase);
        subAccount = normalizeNullable(subAccount);
    }

    // =====================================================
    // HELPERS (НЕ КОЛОНКИ)
    // =====================================================

    /**
     * Минимальный набор для любой биржи: apiKey + apiSecret.
     * Требования (например passphrase для OKX) — проверяются в сервисах диагностики/клиентах биржи.
     */
    @Transient
    public boolean hasBaseKeys() {
        return notBlank(apiKey) && notBlank(apiSecret);
    }

    /**
     * Удобно для UI: есть ли вообще что-то введённое (хотя бы один секрет).
     */
    @Transient
    public boolean hasAnySecret() {
        return notBlank(apiKey) || notBlank(apiSecret) || notBlank(passphrase);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String normalizeUpper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static String normalizeNullable(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }
}
