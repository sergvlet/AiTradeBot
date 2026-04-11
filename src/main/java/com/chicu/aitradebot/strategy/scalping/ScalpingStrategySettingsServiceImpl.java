package com.chicu.aitradebot.strategy.scalping;

import com.chicu.aitradebot.ai.override.AiOverrideService;
import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.service.StrategySettingsService;
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
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScalpingStrategySettingsServiceImpl implements ScalpingStrategySettingsService {

    private final ScalpingStrategySettingsRepository repo;
    private final AiOverrideService aiOverrideService;
    private final ObjectMapper mapper;
    private final StrategySettingsService strategySettingsService;

    @Override
    public ScalpingStrategySettings getOrCreate(Long chatId) {
        return applyTradeContextOverlay(loadBase(chatId));
    }

    @Override
    public ScalpingStrategySettings save(ScalpingStrategySettings settings) {
        if (settings == null) {
            return null;
        }
        settings.normalize();
        ScalpingStrategySettings saved = repo.save(settings);
        return applyTradeContextOverlay(saved);
    }

    @Override
    public ScalpingStrategySettings update(Long chatId, ScalpingStrategySettings incoming) {
        ScalpingStrategySettings s = loadBase(chatId);
        if (incoming == null) {
            return applyTradeContextOverlay(s);
        }

        copyIfNotNull(incoming.getWindowSize(), s::setWindowSize);
        copyIfNotNull(incoming.getMinImpulsePct(), s::setMinImpulsePct);
        copyIfNotNull(incoming.getEmaDiffThreshold(), s::setEmaDiffThreshold);
        copyIfNotNull(incoming.getVolumeRatio(), s::setVolumeRatio);
        copyIfNotNull(incoming.getSpreadLimitPct(), s::setSpreadLimitPct);
        copyIfNotNull(incoming.getAtrPctRange(), s::setAtrPctRange);
        copyIfNotNull(incoming.getRsiFilter(), s::setRsiFilter);
        copyIfNotNull(incoming.getRiskRewardMin(), s::setRiskRewardMin);
        copyIfNotNull(incoming.getOrderVolume(), s::setOrderVolume);
        copyIfNotNull(incoming.getTakeProfitPct(), s::setTakeProfitPct);
        copyIfNotNull(incoming.getStopLossPct(), s::setStopLossPct);

        copyIfNotNull(incoming.getRegimeAutoEnabled(), s::setRegimeAutoEnabled);
        copyIfNotNull(incoming.getAllowTrendTrades(), s::setAllowTrendTrades);
        copyIfNotNull(incoming.getAllowRangeTrades(), s::setAllowRangeTrades);
        copyIfNotNull(incoming.getAllowBreakoutTrades(), s::setAllowBreakoutTrades);
        copyIfNotNull(incoming.getAllowCounterTrendTrades(), s::setAllowCounterTrendTrades);

        copyIfNotNull(incoming.getChaosBlockThreshold(), s::setChaosBlockThreshold);
        copyIfNotNull(incoming.getSqueezeThreshold(), s::setSqueezeThreshold);

        copyIfNotNull(incoming.getTrendMinScore(), s::setTrendMinScore);
        copyIfNotNull(incoming.getPullbackMaxDepthPct(), s::setPullbackMaxDepthPct);
        copyIfNotNull(incoming.getPullbackEntryBufferPct(), s::setPullbackEntryBufferPct);
        copyIfNotNull(incoming.getTrendTpPct(), s::setTrendTpPct);
        copyIfNotNull(incoming.getTrendSlPct(), s::setTrendSlPct);
        copyIfNotNull(incoming.getTrendBreakEvenPct(), s::setTrendBreakEvenPct);
        copyIfNotNull(incoming.getTrendMaxHoldSec(), s::setTrendMaxHoldSec);

        copyIfNotNull(incoming.getRangeMinScore(), s::setRangeMinScore);
        copyIfNotNull(incoming.getRangeEntryFromLowPct(), s::setRangeEntryFromLowPct);
        copyIfNotNull(incoming.getRangeExitToMidPct(), s::setRangeExitToMidPct);
        copyIfNotNull(incoming.getRangeTpPct(), s::setRangeTpPct);
        copyIfNotNull(incoming.getRangeSlPct(), s::setRangeSlPct);
        copyIfNotNull(incoming.getRangeMaxHoldSec(), s::setRangeMaxHoldSec);

        copyIfNotNull(incoming.getBreakoutMinScore(), s::setBreakoutMinScore);
        copyIfNotNull(incoming.getBreakoutVolumeFactor(), s::setBreakoutVolumeFactor);
        copyIfNotNull(incoming.getBreakoutTpPct(), s::setBreakoutTpPct);
        copyIfNotNull(incoming.getBreakoutSlPct(), s::setBreakoutSlPct);

        copyIfNotNull(incoming.getMaxSpreadPct(), s::setMaxSpreadPct);
        copyIfNotNull(incoming.getMinAtrPct(), s::setMinAtrPct);
        copyIfNotNull(incoming.getMaxAtrPct(), s::setMaxAtrPct);
        copyIfNotNull(incoming.getMinVolumeRatio(), s::setMinVolumeRatio);
        copyIfNotNull(incoming.getMinRiskReward(), s::setMinRiskReward);
        copyIfNotNull(incoming.getCooldownAfterStopSec(), s::setCooldownAfterStopSec);
        copyIfNotNull(incoming.getCooldownAfterExitSec(), s::setCooldownAfterExitSec);
        copyIfNotNull(incoming.getMaxConsecutiveStops(), s::setMaxConsecutiveStops);
        copyIfNotNull(incoming.getReentryLockSec(), s::setReentryLockSec);

        copyIfNotNull(incoming.getPartialExitEnabled(), s::setPartialExitEnabled);
        copyIfNotNull(incoming.getPartialExitPct(), s::setPartialExitPct);
        copyIfNotNull(incoming.getPartialExitTriggerPct(), s::setPartialExitTriggerPct);
        copyIfNotNull(incoming.getEmergencyChaosExitEnabled(), s::setEmergencyChaosExitEnabled);
        copyIfNotNull(incoming.getUseIntrabarConfirmation(), s::setUseIntrabarConfirmation);
        copyIfNotNull(incoming.getMicroWindowSize(), s::setMicroWindowSize);

        // ВАЖНО:
        // symbol / timeframe / cachedCandlesLimit / active
        // не принимаем из Advanced UI.
        // Их источник истины — StrategySettings (вкладка "Торговля" / runtime-контекст).

        s.normalize();
        ScalpingStrategySettings saved = repo.save(s);
        return applyTradeContextOverlay(saved);
    }

    @Override
    public SettingsSnapshot getSnapshot(long chatId) {
        ScalpingStrategySettings s = getEffective(chatId);

        return SettingsSnapshot.builder()
                .chatId(chatId)
                .put("strategy", "SCALPING")
                .put("active", s.getActive())
                .put("windowSize", s.getWindowSize())
                .put("minImpulsePct", normalize(s.getMinImpulsePct()))
                .put("emaDiffThreshold", normalize(s.getEmaDiffThreshold()))
                .put("volumeRatio", s.getVolumeRatio())
                .put("spreadLimitPct", normalize(s.getSpreadLimitPct()))
                .put("atrPctRange", normalize(s.getAtrPctRange()))
                .put("rsiFilter", s.getRsiFilter())
                .put("riskRewardMin", s.getRiskRewardMin())
                .put("orderVolume", s.getOrderVolume())
                .put("takeProfitPct", s.getTakeProfitPct())
                .put("stopLossPct", s.getStopLossPct())
                .put("symbol", s.getSymbol())
                .put("timeframe", s.getTimeframe())
                .put("cachedCandlesLimit", s.getCachedCandlesLimit())

                .put("regimeAutoEnabled", s.getRegimeAutoEnabled())
                .put("allowTrendTrades", s.getAllowTrendTrades())
                .put("allowRangeTrades", s.getAllowRangeTrades())
                .put("allowBreakoutTrades", s.getAllowBreakoutTrades())
                .put("allowCounterTrendTrades", s.getAllowCounterTrendTrades())
                .put("chaosBlockThreshold", normalize(s.getChaosBlockThreshold()))
                .put("squeezeThreshold", normalize(s.getSqueezeThreshold()))

                .put("trendMinScore", normalize(s.getTrendMinScore()))
                .put("pullbackMaxDepthPct", normalize(s.getPullbackMaxDepthPct()))
                .put("pullbackEntryBufferPct", normalize(s.getPullbackEntryBufferPct()))
                .put("trendTpPct", normalize(s.getTrendTpPct()))
                .put("trendSlPct", normalize(s.getTrendSlPct()))
                .put("trendBreakEvenPct", normalize(s.getTrendBreakEvenPct()))
                .put("trendMaxHoldSec", s.getTrendMaxHoldSec())

                .put("rangeMinScore", normalize(s.getRangeMinScore()))
                .put("rangeEntryFromLowPct", normalize(s.getRangeEntryFromLowPct()))
                .put("rangeExitToMidPct", normalize(s.getRangeExitToMidPct()))
                .put("rangeTpPct", normalize(s.getRangeTpPct()))
                .put("rangeSlPct", normalize(s.getRangeSlPct()))
                .put("rangeMaxHoldSec", s.getRangeMaxHoldSec())

                .put("breakoutMinScore", normalize(s.getBreakoutMinScore()))
                .put("breakoutVolumeFactor", normalize(s.getBreakoutVolumeFactor()))
                .put("breakoutTpPct", normalize(s.getBreakoutTpPct()))
                .put("breakoutSlPct", normalize(s.getBreakoutSlPct()))

                .put("maxSpreadPct", normalize(s.getMaxSpreadPct()))
                .put("minAtrPct", normalize(s.getMinAtrPct()))
                .put("maxAtrPct", normalize(s.getMaxAtrPct()))
                .put("minVolumeRatio", normalize(s.getMinVolumeRatio()))
                .put("minRiskReward", normalize(s.getMinRiskReward()))
                .put("cooldownAfterStopSec", s.getCooldownAfterStopSec())
                .put("cooldownAfterExitSec", s.getCooldownAfterExitSec())
                .put("maxConsecutiveStops", s.getMaxConsecutiveStops())
                .put("reentryLockSec", s.getReentryLockSec())
                .put("partialExitEnabled", s.getPartialExitEnabled())
                .put("partialExitPct", normalize(s.getPartialExitPct()))
                .put("partialExitTriggerPct", normalize(s.getPartialExitTriggerPct()))
                .put("emergencyChaosExitEnabled", s.getEmergencyChaosExitEnabled())
                .put("useIntrabarConfirmation", s.getUseIntrabarConfirmation())
                .put("microWindowSize", s.getMicroWindowSize())
                .build();
    }

    @Override
    public ScalpingStrategySettings getEffective(Long chatId) {
        ScalpingStrategySettings base = applyTradeContextOverlay(loadBase(chatId));

        var patchOpt = aiOverrideService.getActivePatch(chatId, StrategyType.SCALPING, Instant.now());
        if (patchOpt.isEmpty() || patchOpt.get().isEmpty()) {
            return applyRuntimeProfile(base, false);
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
        effective.setUpdatedAt(base.getUpdatedAt());
        return applyRuntimeProfile(effective, true);
    }

    private ScalpingStrategySettings loadBase(Long chatId) {
        return repo.findTopByChatIdOrderByIdDesc(chatId)
                .orElseGet(() -> {
                    ScalpingStrategySettings def = ScalpingStrategySettings.builder()
                            .chatId(chatId)
                            .build();
                    def.normalize();
                    log.info("🆕 Созданы настройки нового SCALPING V4 chatId={}", chatId);
                    return repo.save(def);
                });
    }

    private ScalpingStrategySettings applyTradeContextOverlay(ScalpingStrategySettings base) {
        if (base == null) {
            return null;
        }

        ScalpingStrategySettings merged = base.toBuilder().build();

        if (merged.getChatId() == null) {
            merged.normalize();
            return merged;
        }

        try {
            StrategySettings ss = strategySettingsService.getSettings(merged.getChatId(), StrategyType.SCALPING);
            if (ss == null) {
                merged.normalize();
                return merged;
            }

            String symbol = normUpper(ss.getSymbol());
            if (symbol != null) {
                merged.setSymbol(symbol);
            }

            String timeframe = normLower(ss.getTimeframe());
            if (timeframe != null) {
                merged.setTimeframe(timeframe);
            }

            Integer cachedCandlesLimit = resolveCachedCandlesLimit(ss);
            if (cachedCandlesLimit != null) {
                merged.setCachedCandlesLimit(cachedCandlesLimit);
            }

            Boolean active = resolveActive(ss);
            if (active != null) {
                merged.setActive(active);
            }

            merged.normalize();
            return merged;
        } catch (Exception e) {
            log.warn("⚠️ Не удалось наложить trade-context на SCALPING settings chatId={} err={}",
                    merged.getChatId(), e.toString());
            merged.normalize();
            return merged;
        }
    }

    private ScalpingStrategySettings applyRuntimeProfile(ScalpingStrategySettings settings, boolean patched) {
        if (settings == null) {
            return null;
        }

        ScalpingStrategySettings s = settings.toBuilder().build();
        boolean changed = false;

        String tf = s.getTimeframe() == null ? "1m" : s.getTimeframe().trim().toLowerCase();
        if ("1m".equals(tf)) {
            if (s.getWindowSize() == null || s.getWindowSize() > 48) {
                s.setWindowSize(48);
                changed = true;
            }
            if (s.getMicroWindowSize() == null || s.getMicroWindowSize() > 10) {
                s.setMicroWindowSize(8);
                changed = true;
            }
            if (s.getCachedCandlesLimit() == null || s.getCachedCandlesLimit() < 900) {
                s.setCachedCandlesLimit(1200);
                changed = true;
            }
        } else if ("3m".equals(tf)) {
            if (s.getWindowSize() == null || s.getWindowSize() > 56) {
                s.setWindowSize(56);
                changed = true;
            }
            if (s.getCachedCandlesLimit() == null || s.getCachedCandlesLimit() < 700) {
                s.setCachedCandlesLimit(1000);
                changed = true;
            }
        }

        s.normalize();
        if (changed) {
            log.info("⚙️ Применён runtime-профиль SCALPING chatId={} patched={} tf={} window={} candles={} trendTp={} trendSl={} rangeTp={} rangeSl={}",
                    s.getChatId(),
                    patched,
                    s.getTimeframe(),
                    s.getWindowSize(),
                    s.getCachedCandlesLimit(),
                    s.getTrendTpPct(),
                    s.getTrendSlPct(),
                    s.getRangeTpPct(),
                    s.getRangeSlPct());
        }
        return s;
    }

    private static Integer resolveCachedCandlesLimit(StrategySettings ss) {
        if (ss == null) {
            return null;
        }
        try {
            return ss.getCachedCandlesLimit();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Boolean resolveActive(StrategySettings ss) {
        if (ss == null) {
            return null;
        }
        try {
            return ss.isActive();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normUpper(String value) {
        if (value == null) return null;
        String s = value.trim().toUpperCase();
        return s.isEmpty() ? null : s;
    }

    private static String normLower(String value) {
        if (value == null) return null;
        String s = value.trim().toLowerCase();
        return s.isEmpty() ? null : s;
    }

    private static <T> void copyIfNotNull(T value, Consumer<T> consumer) {
        if (value != null) {
            consumer.accept(value);
        }
    }

    private static Double normalize(Double v) {
        if (v == null) {
            return null;
        }
        return BigDecimal.valueOf(v).stripTrailingZeros().doubleValue();
    }
}
