package com.chicu.aitradebot.ai.tuning.candidates;

import com.chicu.aitradebot.ai.persistence.ParamValueType;
import com.chicu.aitradebot.ai.tuning.TuningCandidate;
import com.chicu.aitradebot.ai.tuning.space.ParamSpaceItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Slf4j
@Service
public class RandomCandidateGenerator implements CandidateGenerator {

    @Override
    public List<TuningCandidate> generate(Map<String, ParamSpaceItem> space, int count, long seed) {
        if (space == null || space.isEmpty()) {
            return List.of();
        }
        if (count <= 0) {
            return List.of();
        }

        Random rnd = new Random(seed);
        List<TuningCandidate> out = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            Map<String, Object> params = new LinkedHashMap<>();

            for (ParamSpaceItem item : space.values()) {
                Object value = generateValue(item, rnd);
                params.put(item.name(), value);
            }

            out.add(TuningCandidate.builder().params(params).build());
        }

        return out;
    }

    private Object generateValue(ParamSpaceItem item, Random rnd) {
        if (item == null) throw new IllegalArgumentException("ParamSpaceItem is null");
        if (item.name() == null || item.name().isBlank()) {
            throw new IllegalArgumentException("ParamSpaceItem.name is empty");
        }
        if (item.type() == null) {
            throw new IllegalArgumentException("ParamSpaceItem.type is null for " + item.name());
        }

        ParamValueType type = item.type();

        return switch (type) {
            case INT -> generateInt(item, rnd);
            case DECIMAL -> generateDecimal(item, rnd);
            case BOOLEAN -> rnd.nextBoolean();
            case STRING -> throw new IllegalArgumentException("STRING ParamSpace пока не поддержан: " + item.name());
        };
    }

    private Integer generateInt(ParamSpaceItem item, Random rnd) {
        BigDecimal min = must(item.min(), item.name(), "min");
        BigDecimal max = must(item.max(), item.name(), "max");
        BigDecimal step = must(item.step(), item.name(), "step");

        long steps = stepsCount(min, max, step, item.name());
        long k = nextLongInclusive(rnd, 0, steps);

        BigDecimal v = min.add(step.multiply(BigDecimal.valueOf(k)));
        return v.intValueExact();
    }

    private BigDecimal generateDecimal(ParamSpaceItem item, Random rnd) {
        BigDecimal min = must(item.min(), item.name(), "min");
        BigDecimal max = must(item.max(), item.name(), "max");
        BigDecimal step = must(item.step(), item.name(), "step");

        long steps = stepsCount(min, max, step, item.name());
        long k = nextLongInclusive(rnd, 0, steps);

        BigDecimal v = min.add(step.multiply(BigDecimal.valueOf(k)));

        // аккуратно нормализуем “красоту” (без потери точности)
        return v.stripTrailingZeros();
    }

    private static BigDecimal must(BigDecimal v, String name, String field) {
        if (v == null) throw new IllegalArgumentException("ParamSpace: " + name + " missing " + field);
        return v;
    }

    private static long stepsCount(BigDecimal min, BigDecimal max, BigDecimal step, String name) {
        if (step.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("ParamSpace: " + name + " step <= 0");
        }
        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException("ParamSpace: " + name + " min > max");
        }

        BigDecimal range = max.subtract(min);
        // floor((max-min)/step)
        BigDecimal raw = range.divide(step, 0, RoundingMode.DOWN);

        try {
            return raw.longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("ParamSpace: " + name + " steps too large: " + raw, ex);
        }
    }

    private static long nextLongInclusive(Random rnd, long min, long max) {
        if (max < min) throw new IllegalArgumentException("nextLongInclusive: max < min");
        if (max == min) return min;

        long bound = (max - min) + 1;
        long r = nextLongBounded(rnd, bound);
        return min + r;
    }

    private static long nextLongBounded(Random rnd, long bound) {
        // аналог Random#nextInt(bound), но для long
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");

        long r = rnd.nextLong();
        long m = bound - 1;

        if ((bound & m) == 0L) { // power of two
            return r & m;
        }

        long u = r >>> 1;
        while (u + m - (u % bound) < 0L) {
            u = (rnd.nextLong() >>> 1);
        }
        return u % bound;
    }
}
