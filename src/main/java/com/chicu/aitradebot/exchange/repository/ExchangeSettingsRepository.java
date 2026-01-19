package com.chicu.aitradebot.exchange.repository;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.domain.ExchangeSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExchangeSettingsRepository extends JpaRepository<ExchangeSettings, Long> {

    Optional<ExchangeSettings> findByChatIdAndExchangeAndNetwork(Long chatId, String exchange, NetworkType network);

    List<ExchangeSettings> findAllByChatId(Long chatId);

    void deleteByChatIdAndExchangeAndNetwork(Long chatId, String exchange, NetworkType network);
}
