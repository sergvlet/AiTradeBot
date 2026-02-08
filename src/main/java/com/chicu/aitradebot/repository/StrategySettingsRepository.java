package com.chicu.aitradebot.repository;

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

    List<StrategySettings> findAllByChatId(long chatId);

    Optional<StrategySettings> findByChatIdAndType(long chatId, StrategyType type);

    List<StrategySettings> findAllByChatIdAndTypeOrderByUpdatedAtDescIdDesc(long chatId, StrategyType type);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           select s
           from StrategySettings s
           where s.chatId = :chatId
             and s.type = :type
           """)
    Optional<StrategySettings> findByChatIdAndTypeForUpdate(
            @Param("chatId") long chatId,
            @Param("type") StrategyType type
    );

    @Modifying
    @Transactional
    @Query("""
           delete from StrategySettings s
           where s.chatId = :chatId
             and s.type = :type
             and s.id <> :keepId
           """)
    int deleteDuplicatesByChatIdAndTypeKeepId(
            @Param("chatId") long chatId,
            @Param("type") StrategyType type,
            @Param("keepId") long keepId
    );
}
