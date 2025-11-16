package com.chicu.aitradebot.repository;

import com.chicu.aitradebot.domain.Balance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface BalanceRepository extends JpaRepository<Balance, Long> {

    // 🔹 Получить все балансы пользователя
    List<Balance> findAllByUserId(Long userId);

    // 🔹 Найти конкретный актив
    Optional<Balance> findByUserIdAndAsset(Long userId, String asset);

    // 🔹 Получить только свободный баланс
    @Query("SELECT b.free FROM Balance b WHERE b.userId = :userId AND b.asset = :asset")
    BigDecimal findFreeByUserIdAndAsset(Long userId, String asset);

    // 🔹 Получить общий баланс (free + locked)
    @Query("SELECT (b.free + b.locked) FROM Balance b WHERE b.userId = :userId AND b.asset = :asset")
    BigDecimal findTotalByUserIdAndAsset(Long userId, String asset);
}
