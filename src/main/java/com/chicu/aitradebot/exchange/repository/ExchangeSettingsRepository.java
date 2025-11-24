package com.chicu.aitradebot.exchange.repository;

import com.chicu.aitradebot.domain.ExchangeSettings;
import com.chicu.aitradebot.common.enums.NetworkType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExchangeSettingsRepository extends JpaRepository<ExchangeSettings, Long> {

    Optional<ExchangeSettings> findByChatIdAndExchangeAndNetwork(Long chatId, String exchange, NetworkType network);

    List<ExchangeSettings> findAllByChatId(Long chatId);

}
