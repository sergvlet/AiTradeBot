package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.ai.override.AiOverrideService;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.strategy.core.SettingsSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScalpingStrategySettingsServiceImpl implements ScalpingStrategySettingsService {

    private final ScalpingStrategySettingsRepository repo;
    private final AiOverrideService aiOverrideService;
    private final ObjectMapper mapper;

    @Override
    public ScalpingStrategySettings getOrCreate(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    ScalpingStrategySettings def = ScalpingStrategySettings.builder()
                            .chatId(chatId)
                            .active(false)
                            .windowSize(60)
                            .minImpulsePct(0.08d)
                            .emaDiffThreshold(0.05d)
                            .volumeRatio(1.00d)
                            .spreadLimitPct(0.35d)
                            .atrPctRange(0.90d)
                            .rsiFilter(38.0d)
                            .riskRewardMin(1.10d)
                            .orderVolume(20.0d)
                            .takeProfitPct(0.28d)
                            .stopLossPct(0.18d)
                            .symbol("BTCUSDT")
                            .timeframe("1m")
                            .cachedCandlesLimit(1000)
                            .build();

                    def.normalize();
                    log.info("🆕 Созданы настройки SCALPING V4 (chatId={})", chatId);
                    return repo.save(def);
                });
    }

    @Override
    public ScalpingStrategySettings save(ScalpingStrategySettings settings) {
        if (settings == null) {
            return null;
        }
        settings.normalize();
        return repo.save(settings);
    }

    @Override
    public ScalpingStrategySettings update(Long chatId, ScalpingStrategySettings incoming) {
        ScalpingStrategySettings s = getOrCreate(chatId);

        if (incoming == null) {
            return s;
        }

        setIfPositiveInt(incoming.getWindowSize(), s::setWindowSize);
        setIfPositive(incoming.getMinImpulsePct(), s::setMinImpulsePct);
        setIfPositive(incoming.getEmaDiffThreshold(), s::setEmaDiffThreshold);
        setIfPositive(incoming.getVolumeRatio(), s::setVolumeRatio);
        setIfPositive(incoming.getSpreadLimitPct(), s::setSpreadLimitPct);
        setIfPositive(incoming.getAtrPctRange(), s::setAtrPctRange);
        setIfPositive(incoming.getRsiFilter(), s::setRsiFilter);
        setIfPositive(incoming.getRiskRewardMin(), s::setRiskRewardMin);
        setIfPositive(incoming.getOrderVolume(), s::setOrderVolume);
        setIfPositive(incoming.getTakeProfitPct(), s::setTakeProfitPct);
        setIfPositive(incoming.getStopLossPct(), s::setStopLossPct);
        setIfPositiveInt(incoming.getCachedCandlesLimit(), s::setCachedCandlesLimit);

        if (incoming.getSymbol() != null && !incoming.getSymbol().isBlank()) {
            s.setSymbol(incoming.getSymbol().trim().toUpperCase());
        }
        if (incoming.getTimeframe() != null && !incoming.getTimeframe().isBlank()) {
            s.setTimeframe(incoming.getTimeframe().trim().toLowerCase());
        }
        if (incoming.getActive() != null) {
            s.setActive(incoming.getActive());
        }

        s.normalize();
        return repo.save(s);
    }

    @Override
    public SettingsSnapshot getSnapshot(long chatId) {
        ScalpingStrategySettings s = getEffective(chatId);

        return SettingsSnapshot.builder()
                .chatId(chatId)
                .put("strategy", "SCALPING")
                .put("active", s.getActive())
                .put("windowSize", s.getWindowSize())
                .put("minImpulsePct", normalizePercentLike(s.getMinImpulsePct()))
                .put("emaDiffThreshold", normalizePercentLike(s.getEmaDiffThreshold()))
                .put("volumeRatio", s.getVolumeRatio())
                .put("spreadLimitPct", normalizePercentLike(s.getSpreadLimitPct()))
                .put("atrPctRange", normalizePercentLike(s.getAtrPctRange()))
                .put("rsiFilter", s.getRsiFilter())
                .put("riskRewardMin", s.getRiskRewardMin())
                .put("orderVolume", s.getOrderVolume())
                .put("takeProfitPct", s.getTakeProfitPct())
                .put("stopLossPct", s.getStopLossPct())
                .put("symbol", s.getSymbol())
                .put("timeframe", s.getTimeframe())
                .put("cachedCandlesLimit", s.getCachedCandlesLimit())
                .build();
    }

    @Override
    public ScalpingStrategySettings getEffective(Long chatId) {
        ScalpingStrategySettings base = getOrCreate(chatId);

        var patchOpt = aiOverrideService.getActivePatch(chatId, StrategyType.SCALPING, Instant.now());
        if (patchOpt.isEmpty() || patchOpt.get().isEmpty()) {
            return applyScalpProfile(base, false);
        }

        Map<String, Object> patch = new HashMap<>(patchOpt.get());
        patch.remove("id");
        patch.remove("chatId");
        patch.remove("createdAt");
        patch.remove("updatedAt");
        patch.remove("version");

        Map<String, Object> baseMap = mapper.convertValue(base, new TypeReference<Map<String, Object>>() {});
        baseMap.putAll(patch);

        ScalpingStrategySettings effective = mapper.convertValue(baseMap, ScalpingStrategySettings.class);
        effective.setId(base.getId());
        effective.setChatId(base.getChatId());
        effective.setVersion(base.getVersion());
        effective.setCreatedAt(base.getCreatedAt());

        return applyScalpProfile(effective, true);
    }

    private ScalpingStrategySettings applyScalpProfile(ScalpingStrategySettings settings, boolean patched) {
        if (settings == null) {
            return null;
        }

        ScalpingStrategySettings s = settings.toBuilder().build();
        boolean changed = false;

        String tf = s.getTimeframe() == null ? "" : s.getTimeframe().trim().toLowerCase();

        if ("1m".equals(tf) || "3m".equals(tf)) {
            if (s.getWindowSize() == null || s.getWindowSize() > 72) {
                s.setWindowSize(72);
                changed = true;
            }
            if (s.getMinImpulsePct() == null || s.getMinImpulsePct() > 0.10d) {
                s.setMinImpulsePct(0.10d);
                changed = true;
            }
            if (s.getEmaDiffThreshold() == null || s.getEmaDiffThreshold() > 0.06d) {
                s.setEmaDiffThreshold(0.06d);
                changed = true;
            }
            if (s.getVolumeRatio() == null || s.getVolumeRatio() > 1.08d) {
                s.setVolumeRatio(1.05d);
                changed = true;
            }
            if (s.getSpreadLimitPct() == null || s.getSpreadLimitPct() < 0.20d) {
                s.setSpreadLimitPct(0.35d);
                changed = true;
            }
            if (s.getAtrPctRange() == null || s.getAtrPctRange() < 0.50d) {
                s.setAtrPctRange(0.90d);
                changed = true;
            }
            if (s.getRsiFilter() == null || s.getRsiFilter() > 42.0d) {
                s.setRsiFilter(42.0d);
                changed = true;
            }
            if (s.getRiskRewardMin() == null || s.getRiskRewardMin() > 1.20d) {
                s.setRiskRewardMin(1.10d);
                changed = true;
            }
            if (s.getTakeProfitPct() == null || s.getTakeProfitPct() > 0.30d) {
                s.setTakeProfitPct(0.28d);
                changed = true;
            }
            if (s.getStopLossPct() == null || s.getStopLossPct() > 0.20d) {
                s.setStopLossPct(0.18d);
                changed = true;
            }
            if (s.getCachedCandlesLimit() == null || s.getCachedCandlesLimit() < 500) {
                s.setCachedCandlesLimit(1000);
                changed = true;
            }
        }

        s.normalize();

        if (changed) {
            log.info("⚙️ [SCALPING] runtime-profile applied chatId={} patched={} tf={} window={} impulse={} emaDiff={} volumeRatio={} spread={} atr={} rsi={} rr={} tp={} sl={}",
                    s.getChatId(),
                    patched,
                    s.getTimeframe(),
                    s.getWindowSize(),
                    s.getMinImpulsePct(),
                    s.getEmaDiffThreshold(),
                    s.getVolumeRatio(),
                    s.getSpreadLimitPct(),
                    s.getAtrPctRange(),
                    s.getRsiFilter(),
                    s.getRiskRewardMin(),
                    s.getTakeProfitPct(),
                    s.getStopLossPct());
        }

        return s;
    }

    private static void setIfPositive(Double v, java.util.function.Consumer<Double> setter) {
        if (v != null && v > 0) {
            setter.accept(v);
        }
    }

    private static void setIfPositiveInt(Integer v, java.util.function.Consumer<Integer> setter) {
        if (v != null && v > 0) {
            setter.accept(v);
        }
    }

    private static Object normalizePercentLike(Double v) {
        if (v == null) {
            return null;
        }
        return BigDecimal.valueOf(v).stripTrailingZeros().doubleValue();
    }
}
