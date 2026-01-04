package com.chicu.aitradebot.ml.tuning.scalping;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.ml.persistence.TuningRunEntity;
import com.chicu.aitradebot.ml.persistence.TuningRunRepository;
import com.chicu.aitradebot.ml.tuning.TuningCandidate;
import com.chicu.aitradebot.ml.tuning.guard.GuardDecision;
import com.chicu.aitradebot.ml.tuning.guard.TuningGuard;
import com.chicu.aitradebot.ml.tuning.guard.TuningGuardProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScalpingTuningGuard implements TuningGuard {

    private static final String TP = "takeProfitPct";
    private static final String SL = "stopLossPct";

    private final TuningRunRepository runRepo;
    private final TuningGuardProperties props;

    @Override
    public StrategyType getStrategyType() {
        return StrategyType.SCALPING;
    }

    @Override
    public GuardDecision checkFrequency(Long chatId) {
        if (!props.isEnabled()) return GuardDecision.allow();

        List<TuningRunEntity> last = runRepo.findTop50ByChatIdAndStrategyTypeOrderByCreatedAtDesc(chatId, getStrategyType());
        if (last.isEmpty()) return GuardDecision.allow();

        Instant lastAt = last.getFirst().getCreatedAt();
        if (lastAt == null) return GuardDecision.allow();

        long minHours = props.getMinHoursBetween();
        Duration since = Duration.between(lastAt, Instant.now());

        if (since.toHours() < minHours) {
            return GuardDecision.deny("Слишком часто: прошло " + since.toHours() + "h, нужно минимум " + minHours + "h");
        }

        return GuardDecision.allow();
    }

    @Override
    public GuardDecision checkCandidate(Long chatId, Map<String, Object> currentParams, TuningCandidate candidate) {
        if (!props.isEnabled()) return GuardDecision.allow();

        Map<String, Object> cand = candidate != null ? candidate.params() : null;
        if (cand == null || cand.isEmpty()) {
            return GuardDecision.deny("Пустой кандидат");
        }

        // 1) TP >= SL (если включено)
        if (props.isRequireTpGteSl()) {
            BigDecimal tp = asDecimal(cand.get(TP));
            BigDecimal sl = asDecimal(cand.get(SL));
            if (tp != null && sl != null && tp.compareTo(sl) < 0) {
                return GuardDecision.deny("Запрещено: takeProfitPct < stopLossPct");
            }
        }

        // 2) maxDeltaPct на каждое числовое поле (если текущее значение задано)
        double maxDeltaPct = props.getMaxDeltaPct();
        if (maxDeltaPct > 0 && currentParams != null && !currentParams.isEmpty()) {

            for (Map.Entry<String, Object> e : cand.entrySet()) {
                String key = e.getKey();
                Object newValObj = e.getValue();
                Object oldValObj = currentParams.get(key);

                BigDecimal newVal = asDecimal(newValObj);
                BigDecimal oldVal = asDecimal(oldValObj);

                // если не числовое или нет old — пропускаем (не блокируем)
                if (newVal == null || oldVal == null) continue;
                if (oldVal.compareTo(BigDecimal.ZERO) == 0) continue; // чтобы не делить на 0

                BigDecimal delta = newVal.subtract(oldVal).abs();
                BigDecimal allowed = oldVal.abs().multiply(BigDecimal.valueOf(maxDeltaPct));

                if (delta.compareTo(allowed) > 0) {
                    return GuardDecision.deny("Слишком большой delta для '" + key + "': old=" + oldVal + ", new=" + newVal +
                            ", maxDeltaPct=" + maxDeltaPct);
                }
            }
        }

        return GuardDecision.allow();
    }

    private static BigDecimal asDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Integer i) return BigDecimal.valueOf(i.longValue());
        if (o instanceof Long l) return BigDecimal.valueOf(l);
        if (o instanceof Double d) return BigDecimal.valueOf(d);
        if (o instanceof Float f) return BigDecimal.valueOf(f.doubleValue());
        if (o instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }
}
