package com.chicu.aitradebot.repository;

import com.chicu.aitradebot.common.enums.NetworkType;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface StrategySettingsRepository extends JpaRepository<StrategySettings, Long> {

    // =========================================================
    // LIST
    // =========================================================
    List<StrategySettings> findAllByChatId(long chatId);





    List<StrategySettings> findAllByChatIdAndExchangeName(long chatId, String exchangeName);

    List<StrategySettings> findAllByChatIdAndExchangeNameAndNetworkType(
            long chatId, String exchangeName, NetworkType networkType
    );
    // =========================================================
    // ✅ STRICT KEY (основной метод)
    // =========================================================
    Optional<StrategySettings> findByChatIdAndTypeAndExchangeNameAndNetworkType(
            long chatId,
            StrategyType type,
            String exchangeName,
            NetworkType networkType
    );

    // нужен для дедупа (если до UNIQUE уже наплодились записи)
    List<StrategySettings> findAllByChatIdAndTypeAndExchangeNameAndNetworkTypeOrderByUpdatedAtDescIdDesc(
            long chatId,
            StrategyType type,
            String exchangeName,
            NetworkType networkType
    );

    // =========================================================
    // ✅ PROD: row lock, чтобы параллельные autosave не делали гонки
    // (если строка уже существует)
    // =========================================================
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           select s
           from StrategySettings s
           where s.chatId = :chatId
             and s.type = :type
             and s.exchangeName = :exchangeName
             and s.networkType = :networkType
           """)
    Optional<StrategySettings> findByKeyForUpdate(
            @Param("chatId") long chatId,
            @Param("type") StrategyType type,
            @Param("exchangeName") String exchangeName,
            @Param("networkType") NetworkType networkType
    );

    // =========================================================
    // ✅ DEDUP helpers
    // =========================================================
    @Modifying
    @Transactional
    void deleteByIdIn(List<Long> ids);

    @Modifying
    @Transactional
    @Query("""
           delete from StrategySettings s
           where s.chatId = :chatId
             and s.type = :type
             and s.exchangeName = :exchangeName
             and s.networkType = :networkType
             and s.id <> :keepId
           """)
    int deleteDuplicatesKeepId(
            @Param("chatId") long chatId,
            @Param("type") StrategyType type,
            @Param("exchangeName") String exchangeName,
            @Param("networkType") NetworkType networkType,
            @Param("keepId") long keepId
    );
}
