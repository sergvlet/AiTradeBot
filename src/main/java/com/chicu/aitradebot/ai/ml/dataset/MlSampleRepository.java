package com.chicu.aitradebot.ai.ml.dataset;

import com.chicu.aitradebot.common.enums.StrategyType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Repository
public interface MlSampleRepository
        extends JpaRepository<MlSampleEntity, Long>, JpaSpecificationExecutor<MlSampleEntity> {

    /**
     * Последние сэмплы по chatId + strategyType начиная с указанного времени.
     * Нужен для MlTrainingServiceImpl.findRecent(...)
     */
    default List<MlSampleEntity> findRecent(Long chatId,
                                            StrategyType strategyType,
                                            Instant from) {
        if (chatId == null || strategyType == null || from == null) {
            return List.of();
        }

        return findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("chatId"), chatId));
            predicates.add(cb.equal(root.get("strategyType"), strategyType));
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            return cb.and(predicates.toArray(new Predicate[0]));
        }, Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    /**
     * Безопасная выборка для обучения.
     * Здесь нет конструкции (? is null or ...), из-за которой PostgreSQL
     * у тебя выбрасывал ошибку "не удалось определить тип данных параметра $7".
     */
    default List<MlSampleEntity> findForTraining(Long chatId,
                                                 StrategyType strategyType,
                                                 String symbol,
                                                 String timeframe,
                                                 Instant from,
                                                 Instant to) {
        if (chatId == null || strategyType == null) {
            return List.of();
        }

        final String normalizedSymbol = normalizeSymbol(symbol);
        final String normalizedTimeframe = normalizeTimeframe(timeframe);

        return findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("chatId"), chatId));
            predicates.add(cb.equal(root.get("strategyType"), strategyType));

            if (normalizedSymbol != null) {
                predicates.add(cb.equal(root.get("symbol"), normalizedSymbol));
            }

            if (normalizedTimeframe != null) {
                predicates.add(cb.equal(root.get("timeframe"), normalizedTimeframe));
            }

            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }

            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }

            predicates.add(cb.isNotNull(root.get("label")));
            predicates.add(cb.notEqual(root.get("label"), ""));

            return cb.and(predicates.toArray(new Predicate[0]));
        }, Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null) {
            return null;
        }
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String normalizeTimeframe(String timeframe) {
        if (timeframe == null) {
            return null;
        }
        String s = timeframe.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}

