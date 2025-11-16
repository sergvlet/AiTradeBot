package com.chicu.aitradebot.strategy.registry;

import com.chicu.aitradebot.orchestrator.dto.StrategyRunInfo;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

@Slf4j
public class StrategySettingsMapper {

    public static void fill(StrategyRunInfo.StrategyRunInfoBuilder b, Object s) {
        try {

            setIf(b, "exchange", get(s, "getExchange"));
            setIf(b, "networkType", get(s, "getNetworkType"));
            setIf(b, "timeframe", get(s, "getTimeframe"));
            setIf(b, "tpPct", get(s, "getTpPct", "getTakeProfitPct", "getTakeProfitAtrMult"));
            setIf(b, "slPct", get(s, "getSlPct", "getStopLossPct", "getStopLossAtrMult"));
            setIf(b, "riskPerTradePct", get(s, "getRiskPerTradePct"));
            setIf(b, "version", get(s, "getVersion"));

        } catch (Exception e) {
            log.error("Ошибка маппинга настроек {}", s.getClass(), e);
        }
    }

    private static Object get(Object obj, String... methods) {
        for (String m : methods) {
            try {
                Method mm = obj.getClass().getMethod(m);
                return mm.invoke(obj);
            } catch (Exception ignore) {}
        }
        return null;
    }

    private static void setIf(StrategyRunInfo.StrategyRunInfoBuilder b,
                              String field, Object val) {
        if (val == null) return;
        try {
            StrategyRunInfo.StrategyRunInfoBuilder.class
                    .getMethod(field, val.getClass())
                    .invoke(b, val);
        } catch (Exception ignored) {}
    }
}
