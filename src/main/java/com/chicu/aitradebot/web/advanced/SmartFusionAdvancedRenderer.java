// src/main/java/com/chicu/aitradebot/web/advanced/SmartFusionAdvancedRenderer.java
package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettings;
import com.chicu.aitradebot.strategy.smartfusion.SmartFusionStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmartFusionAdvancedRenderer implements StrategyAdvancedRenderer {

    private final SmartFusionStrategySettingsService service;

    @Override
    public StrategyType supports() {
        return StrategyType.SMART_FUSION;
    }

    @Override
    public String render(AdvancedRenderContext ctx) {
        SmartFusionStrategySettings s = service.getOrCreate(ctx.getChatId());

        boolean ro = ctx.isReadOnly();
        String dis = ro ? " disabled" : "";
        String roAttr = ro ? " readonly" : "";

        return ""
                + "<div class='card card-theme p-3 mb-3'>"
                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>SMART_FUSION — параметры стратегии</div>"
                + badge(ro)
                + "  </div>"

                + "  <div class='row g-3'>"

                + fieldBd("weightTech", "Weight TECH", valBd(s.getWeightTech()),
                    "min='0' max='1' step='0.01'", dis, roAttr, "Вес технического источника.")
                + fieldBd("weightMl", "Weight ML", valBd(s.getWeightMl()),
                    "min='0' max='1' step='0.01'", dis, roAttr, "Вес ML источника.")
                + fieldBd("weightRl", "Weight RL", valBd(s.getWeightRl()),
                    "min='0' max='1' step='0.01'", dis, roAttr, "Вес RL источника.")
                + fieldBd("decisionThreshold", "Decision threshold", valBd(s.getDecisionThreshold()),
                    "min='0' max='1' step='0.01'", dis, roAttr, "Порог BUY по итоговому score.")

                + fieldBd("minSourceConfidence", "Min source confidence", valBd(s.getMinSourceConfidence()),
                    "min='0' max='1' step='0.01'", dis, roAttr, "Если ниже — вклад источника = 0.")

                + fieldNumber("rsiPeriod", "RSI period", valI(s.getRsiPeriod()),
                    "min='2' step='1'", dis, roAttr, "Период RSI для TECH.")
                + fieldBd("rsiBuyBelow", "RSI buy below", valBd(s.getRsiBuyBelow()),
                    "min='0' max='100' step='0.1'", dis, roAttr, "Порог покупки по RSI.")
                + fieldBd("rsiSellAbove", "RSI sell above", valBd(s.getRsiSellAbove()),
                    "min='0' max='100' step='0.1'", dis, roAttr, "Порог продажи по RSI.")

                + fieldNumber("emaFast", "EMA fast", valI(s.getEmaFast()),
                    "min='2' step='1'", dis, roAttr, "Период быстрой EMA.")
                + fieldNumber("emaSlow", "EMA slow", valI(s.getEmaSlow()),
                    "min='2' step='1'", dis, roAttr, "Период медленной EMA.")

                + fieldText("mlModelKey", "ML model key", valStr(s.getMlModelKey()), dis, roAttr, "Ключ ML-модели.")
                + fieldText("rlAgentKey", "RL agent key", valStr(s.getRlAgentKey()), dis, roAttr, "Ключ RL-агента.")
                + fieldNumber("lookbackCandles", "Lookback candles", valI(s.getLookbackCandles()),
                    "min='50' step='1'", dis, roAttr, "Сколько свечей брать для контекста.")

                + "  </div>"

                + (ro
                    ? "<div class='alert alert-info small mt-3 mb-0'>Режим <b>AI</b>: ручное редактирование отключено.</div>"
                    : "<div class='alert alert-secondary small mt-3 mb-0'>Режим <b>MANUAL / HYBRID</b>: параметры можно редактировать.</div>"
                )

                + "</div>";
    }

    @Override
    public void handleSubmit(AdvancedRenderContext ctx) {
        if (ctx.getControlMode() == AdvancedControlMode.AI) {
            log.info("🔒 SMART_FUSION advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        SmartFusionStrategySettings in = SmartFusionStrategySettings.builder()
                .chatId(ctx.getChatId())
                .weightTech(parseBd(p.get("weightTech")))
                .weightMl(parseBd(p.get("weightMl")))
                .weightRl(parseBd(p.get("weightRl")))
                .decisionThreshold(parseBd(p.get("decisionThreshold")))
                .minSourceConfidence(parseBd(p.get("minSourceConfidence")))
                .rsiPeriod(parseInt(p.get("rsiPeriod")))
                .rsiBuyBelow(parseBd(p.get("rsiBuyBelow")))
                .rsiSellAbove(parseBd(p.get("rsiSellAbove")))
                .emaFast(parseInt(p.get("emaFast")))
                .emaSlow(parseInt(p.get("emaSlow")))
                .mlModelKey(trimToNull(p.get("mlModelKey")))
                .rlAgentKey(trimToNull(p.get("rlAgentKey")))
                .lookbackCandles(parseInt(p.get("lookbackCandles")))
                .build();

        service.update(ctx.getChatId(), in);
        log.info("✅ SMART_FUSION advanced settings saved (chatId={})", ctx.getChatId());
    }

    // ================= helpers =================

    private static String badge(boolean ro) {
        return ro ? "<span class='badge bg-info'>AI</span>"
                  : "<span class='badge bg-secondary'>MANUAL / HYBRID</span>";
    }

    private static String fieldText(String name, String label, String value, String dis, String roAttr, String help) {
        String v = value == null ? "" : HtmlUtils.htmlEscape(value);
        return ""
                + "<div class='col-md-4'>"
                + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <input type='text' class='form-control' name='" + HtmlUtils.htmlEscape(name) + "' value='" + v + "'" + dis + roAttr + ">"
                + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
                + "</div>";
    }

    private static String fieldNumber(String name, String label, String value, String extraAttrs, String dis, String roAttr, String help) {
        String v = value == null ? "" : HtmlUtils.htmlEscape(value);
        return ""
                + "<div class='col-md-3'>"
                + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <input type='number' class='form-control' name='" + HtmlUtils.htmlEscape(name) + "' value='" + v + "' " + extraAttrs + dis + roAttr + ">"
                + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
                + "</div>";
    }

    private static String fieldBd(String name, String label, String value, String extraAttrs, String dis, String roAttr, String help) {
        return fieldNumber(name, label, value, extraAttrs, dis, roAttr, help);
    }

    private static String valStr(String v) { return v == null ? "" : v; }
    private static String valI(Integer v) { return v == null ? "" : String.valueOf(v); }
    private static String valBd(BigDecimal v) { return v == null ? "" : v.stripTrailingZeros().toPlainString(); }

    private static Integer parseInt(String v) {
        try { return (v == null || v.isBlank()) ? null : Integer.parseInt(v.trim()); }
        catch (Exception e) { return null; }
    }

    private static BigDecimal parseBd(String v) {
        try { return (v == null || v.isBlank()) ? null : new BigDecimal(v.trim()); }
        catch (Exception e) { return null; }
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }
}
