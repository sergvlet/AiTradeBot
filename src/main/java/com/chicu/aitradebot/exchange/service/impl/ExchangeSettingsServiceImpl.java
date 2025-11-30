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

    // ============================================================
    // SECTION 1 — getOrCreate (главный метод)
    // Создаёт пустые настройки если их нет → предотвращает NULL в Thymeleaf
    // ============================================================

    @Override
    @Transactional
    public ExchangeSettings getOrCreate(Long chatId, String exchange, NetworkType network) {
        return repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, network)
                .orElseGet(() -> {
                    ExchangeSettings settings = new ExchangeSettings();
                    settings.setChatId(chatId);
                    settings.setExchange(exchange);
                    settings.setNetwork(network);

                    // важно — избегаем null в шаблоне
                    settings.setApiKey("");
                    settings.setApiSecret("");
                    settings.setPassphrase("");
                    settings.setSubAccount("");

                    settings.setEnabled(false);
                    settings.setCreatedAt(Instant.now());
                    settings.setUpdatedAt(Instant.now());

                    repository.save(settings);

                    log.info("🆕 Созданы новые ExchangeSettings для chatId={} [{} / {}]",
                            chatId, exchange, network);

                    return settings;
                });
    }

    // ============================================================
    // SECTION 2 — получить или кинуть ошибку
    // ============================================================

    @Override
    public ExchangeSettings getOrThrow(Long chatId, String exchange) {
        return repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, NetworkType.MAINNET)
                .orElseThrow(() ->
                        new IllegalStateException("Настройки не найдены: chatId=" + chatId + ", exchange=" + exchange));
    }

    // ============================================================
    // SECTION 3 — сохранение настроек
    // ============================================================

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

        log.info("💾 ExchangeSettings {} / {} обновлены (chatId={})",
                saved.getExchange(), saved.getNetwork(), saved.getChatId());

        return saved;
    }

    // ============================================================
    // SECTION 4 — проверки существования, удаление
    // ============================================================

    @Override
    public boolean exists(Long chatId, String exchange, NetworkType network) {
        return repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, network).isPresent();
    }

    @Override
    @Transactional
    public void delete(Long chatId, String exchange, NetworkType network) {
        repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, network)
                .ifPresent(repository::delete);
        log.warn("🗑️ ExchangeSettings удалены: chatId={}, exchange={}, network={}",
                chatId, exchange, network);
    }

    @Override
    public void deleteById(Long id) {
        repository.deleteById(id);
        log.warn("🗑️ ExchangeSettings удалены по id={}", id);
    }

    // ============================================================
    // SECTION 5 — методы для UI / контроллеров
    // ============================================================

    @Override
    public List<ExchangeSettings> findAllByChatId(Long chatId) {
        return repository.findAllByChatId(chatId);
    }

    @Override
    public Optional<ExchangeSettings> findByChatIdAndExchange(Long chatId, String exchange) {
        return repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, NetworkType.MAINNET);
    }

    @Override
    public Optional<ExchangeSettings> findByChatIdAndExchangeAndNetwork(
            Long chatId, String exchange, NetworkType network
    ) {
        return repository.findByChatIdAndExchangeAndNetwork(chatId, exchange, network);
    }

    // ============================================================
    // SECTION 6 — тест API ключей (быстрая проверка)
    // ============================================================

    @Override
    public boolean testConnection(ExchangeSettings settings) {
        if (settings == null) return false;

        if (isBlank(settings.getExchange()) || settings.getNetwork() == null)
            return false;

        if (isBlank(settings.getApiKey()) || isBlank(settings.getApiSecret()))
            return false;

        boolean looksValid =
                settings.getApiKey().length() >= 8 &&
                settings.getApiSecret().length() >= 8;

        return looksValid;
    }

    // ============================================================
    // SECTION 7 — утилиты
    // ============================================================

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

}
