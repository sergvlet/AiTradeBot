package com.chicu.aitradebot.domain;

import com.chicu.aitradebot.common.enums.NetworkType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

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

    /** Telegram chatId пользователя */
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    /** Название биржи (BINANCE, BYBIT, OKX, KUCOIN и т.п.) */
    @Column(name = "exchange", nullable = false, length = 32)
    private String exchange;

    /** Сеть: MAINNET / TESTNET */
    @Enumerated(EnumType.STRING)
    @Column(name = "network", nullable = false, length = 16)
    private NetworkType network;

    /** Публичный ключ API */
    @Column(name = "api_key", nullable = false, length = 256)
    private String apiKey;

    /** Секретный ключ API */
    @Column(name = "api_secret", nullable = false, length = 256)
    private String apiSecret;

    /** Passphrase (для OKX/Bybit и др.) */
    @Column(name = "passphrase", length = 256)
    private String passphrase;

    /** Субаккаунт (опционально) */
    @Column(name = "sub_account", length = 128)
    private String subAccount;

    /** Активна ли конфигурация */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /** Дата создания */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    /** Дата обновления */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ===================== Утилиты =====================

    /** Упрощённая проверка testnet */
    @Transient
    public boolean isTestnet() {
        return this.network == NetworkType.TESTNET;
    }

    /** Ключ в формате EXCHANGE@NETWORK */
    @Transient
    public String exchangeKey() {
        return (exchange == null ? "UNKNOWN" : exchange) + "@" +
               (network == null ? "MAINNET" : network.name());
    }

    // ===================== Методы совместимости =====================

    /** Старый алиас для isTestnet() — нужен для BinanceExchangeClient */
    @Transient
    public boolean getTestnet() {
        return isTestnet();
    }

    /**
     * Старый алиас для поля apiSecret.
     * Используется для обратной совместимости со старым кодом,
     * где использовался метод getSecretKey().
     */
    @Transient
    public String getSecretKey() {
        return this.apiSecret;
    }
}
