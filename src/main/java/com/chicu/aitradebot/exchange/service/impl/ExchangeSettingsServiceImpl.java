package com.chicu.aitradebot.exchange.service.impl;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.exchange.repository.ExchangeSettingsRepository;
import com.chicu.aitradebot.exchange.service.ExchangeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExchangeSettingsServiceImpl implements ExchangeSettingsService {

    private final ExchangeSettingsRepository repository;

    // ===== CRUD / основной флоу =====

    @Override
    @Transactional
    public ExchangeSettings getOrCreate(Long chatId, String exchange, NetworkType network) {
        return repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, network)
                .orElseGet(() -> {
                    ExchangeSettings settings = new ExchangeSettings();
                    settings.setChatId(chatId);
                    settings.setExchange(exchange);
                    settings.setNetwork(network);
                    settings.setEnabled(false);
                    settings.setCreatedAt(Instant.now());
                    settings.setUpdatedAt(Instant.now());
                    repository.save(settings);
                    log.info("🆕 Созданы новые настройки для chatId={} [{} / {}]", chatId, exchange, network);
                    return settings;
                });
    }

    @Override
    public ExchangeSettings getOrThrow(Long chatId, String exchange) {
        return repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, NetworkType.MAINNET)
                .orElseThrow(() ->
                        new IllegalStateException("Настройки не найдены: chatId=" + chatId + ", exchange=" + exchange));
    }

    @Override
    @Transactional
    public ExchangeSettings save(ExchangeSettings incoming) {
        Optional<ExchangeSettings> existingOpt = repository.findByChatIdAndExchangeAndNetwork(
                incoming.getChatId(), incoming.getExchange(), incoming.getNetwork());

        ExchangeSettings target = existingOpt.orElseGet(ExchangeSettings::new);

        target.setChatId(incoming.getChatId());
        target.setExchange(incoming.getExchange());
        target.setNetwork(incoming.getNetwork());
        target.setApiKey(incoming.getApiKey());
        target.setApiSecret(incoming.getApiSecret());
        target.setPassphrase(incoming.getPassphrase());
        target.setSubAccount(incoming.getSubAccount());
        target.setEnabled(incoming.isEnabled());
        target.setUpdatedAt(Instant.now());

        if (target.getCreatedAt() == null)
            target.setCreatedAt(Instant.now());

        ExchangeSettings saved = repository.save(target);

        log.info("💾 Настройки {} / {} обновлены (chatId={})",
                saved.getExchange(), saved.getNetwork(), saved.getChatId());
        return saved;
    }


    @Override
    public boolean exists(Long chatId, String exchange, NetworkType network) {
        return repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, network).isPresent();
    }

    @Override
    @Transactional
    public void delete(Long chatId, String exchange, NetworkType network) {
        repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, network)
                .ifPresent(repository::delete);
        log.warn("🗑️ Настройки удалены: chatId={}, exchange={}, network={}", chatId, exchange, network);
    }

    // ===== Методы для контроллера / UI =====

    @Override
    public List<ExchangeSettings> findAllByChatId(Long chatId) {
        return repository.findAllByChatId(chatId);
    }

    @Override
    public Optional<ExchangeSettings> findByChatIdAndExchange(Long chatId, String exchange) {
        // По умолчанию считаем MAINNET — как и в getOrThrow
        return repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, NetworkType.MAINNET);
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        repository.deleteById(id);
        log.warn("🗑️ Настройки удалены по id={}", id);
    }

    // ===== Тест соединения =====

    @Override
    public boolean testConnection(ExchangeSettings settings) {
        // На этом этапе делаем "быструю" валидацию, чтобы не тянуть реальные SDK.
        // (Если у тебя есть ExchangeClientFactory — позже подменим на реальный ping/balance)

        if (settings == null) {
            log.warn("❌ testConnection: settings=null");
            return false;
        }
        if (isBlank(settings.getExchange()) || settings.getNetwork() == null) {
            log.warn("❌ testConnection: exchange/network пустые");
            return false;
        }
        if (isBlank(settings.getApiKey()) || isBlank(settings.getApiSecret())) {
            log.warn("❌ testConnection: apiKey/apiSecret пустые");
            return false;
        }

        String exchange = settings.getExchange().trim().toUpperCase();
        NetworkType net = settings.getNetwork();

        // Простой sanity-check по длинам ключей
        boolean looksValid = settings.getApiKey().length() >= 8 && settings.getApiSecret().length() >= 8;

        // Можно добавить специфичные эвристики под биржи
        return switch (exchange) {
            case "BINANCE", "BYBIT", "OKX", "KUCOIN" -> {
                // допустим базовая проверка прошла — считаем валидным
                log.info("🧪 testConnection [{} / {}] apiKey={}, secret=***{}",
                        exchange, net, mask(settings.getApiKey()), tail(settings.getApiSecret()));
                yield looksValid;
            }
            default -> {
                log.warn("⚠️ testConnection: неизвестная биржа '{}'", exchange);
                yield false;
            }
        };
    }

    // ===== Вспомогательные =====

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String mask(String s) {
        if (isBlank(s)) return "";
        return s.length() <= 6 ? "*".repeat(s.length()) : s.substring(0, 3) + "***" + s.substring(s.length() - 3);
    }

    private static String tail(String s) {
        if (isBlank(s) || s.length() <= 4) return s;
        return s.substring(s.length() - 4);
    }

    @Override
    public Optional<ExchangeSettings> findByChatIdAndExchangeAndNetwork(
            Long chatId,
            String exchange,
            NetworkType network
    ) {
        return repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, network);
    }

}
