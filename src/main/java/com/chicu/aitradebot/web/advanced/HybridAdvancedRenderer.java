// src/main/java/com/chicu/aitradebot/web/advanced/HybridAdvancedRenderer.java
package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.hybrid.HybridStrategySettings;
import com.chicu.aitradebot.strategy.hybrid.HybridStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class HybridAdvancedRenderer implements StrategyAdvancedRenderer {

    private final HybridStrategySettingsService service;

    @Override
    public StrategyType supports() {
        return StrategyType.HYBRID;
    }

    @Override
    public String render(AdvancedRenderContext ctx) {
        HybridStrategySettings s = service.getOrCreate(ctx.getChatId());

        boolean ro = ctx.isReadOnly();
        String dis = ro ? " disabled" : "";
        String roAttr = ro ? " readonly" : "";

        return ""
                + "<div class='card card-theme p-3 mb-3'>"
                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>HYBRID — параметры стратегии</div>"
                + badge(ro)
                + "  </div>"

                + "  <div class='row g-3'>"

                + fieldText("mlModelKey", "ML model key", valStr(s.getMlModelKey()), dis, roAttr, "Ключ ML-модели (default и т.п.).")
                + fieldText("rlAgentKey", "RL agent key", valStr(s.getRlAgentKey()), dis, roAttr, "Ключ RL-агента (default и т.п.).")

                + fieldNumber("minConfidence", "Min confidence (0..1)", valD(s.getMinConfidence()),
                    "min='0' max='1' step='0.01'", dis, roAttr, "Общий порог уверенности.")
                + fieldNumber("mlThreshold", "ML threshold (0..1)", valD(s.getMlThreshold()),
                    "min='0' max='1' step='0.01'", dis, roAttr, "Порог ML-сигнала.")
                + fieldNumber("rlMinConfidence", "RL min confidence (0..1)", valD(s.getRlMinConfidence()),
                    "min='0' max='1' step='0.01'", dis, roAttr, "Минимальная уверенность RL.")
                + fieldNumber("lookbackCandles", "Lookback candles", valI(s.getLookbackCandles()),
                    "min='50' step='1'", dis, roAttr, "Сколько свечей брать для контекста/фичей.")

                + fieldBool("allowSingleSourceBuy", "Allow single source BUY",
                    (s.getAllowSingleSourceBuy() != null && s.getAllowSingleSourceBuy()), dis,
                    "Если один источник BUY, а второй HOLD — можно разрешить вход.")

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
            log.info("🔒 HYBRID advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        HybridStrategySettings in = HybridStrategySettings.builder()
                .chatId(ctx.getChatId())
                .mlModelKey(trimToNull(p.get("mlModelKey")))
                .rlAgentKey(trimToNull(p.get("rlAgentKey")))
                .minConfidence(parseDouble(p.get("minConfidence")))
                .allowSingleSourceBuy(parseBoolObj(p.get("allowSingleSourceBuy")))
                .mlThreshold(parseDouble(p.get("mlThreshold")))
                .rlMinConfidence(parseDouble(p.get("rlMinConfidence")))
                .lookbackCandles(parseInt(p.get("lookbackCandles")))
                .build();

        service.update(ctx.getChatId(), in);
        log.info("✅ HYBRID advanced settings saved (chatId={})", ctx.getChatId());
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
                + "<div class='col-md-4'>"
                + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <input type='number' class='form-control' name='" + HtmlUtils.htmlEscape(name) + "' value='" + v + "' " + extraAttrs + dis + roAttr + ">"
                + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
                + "</div>";
    }

    private static String fieldBool(String name, String label, boolean checked, String dis, String help) {
        return ""
                + "<div class='col-md-4'>"
                + "  <label class='form-label d-block'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <div class='form-check form-switch'>"
                + "    <input class='form-check-input' type='checkbox' name='" + HtmlUtils.htmlEscape(name) + "' value='true' " + (checked ? "checked" : "") + dis + ">"
                + "    <label class='form-check-label small text-secondary'>" + HtmlUtils.htmlEscape(help) + "</label>"
                + "  </div>"
                + "</div>";
    }

    private static String valStr(String v) { return v == null ? "" : v; }
    private static String valI(Integer v) { return v == null ? "" : String.valueOf(v); }
    private static String valD(Double v) { return v == null ? "" : String.valueOf(v); }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer parseInt(String v) {
        try { return (v == null || v.isBlank()) ? null : Integer.parseInt(v.trim()); }
        catch (Exception e) { return null; }
    }

    private static Double parseDouble(String v) {
        try { return (v == null || v.isBlank()) ? null : Double.parseDouble(v.trim()); }
        catch (Exception e) { return null; }
    }

    private static Boolean parseBoolObj(String v) {
        if (v == null) return null;
        String s = v.trim().toLowerCase();
        if (s.isEmpty()) return null;
        return s.equals("true") || s.equals("1") || s.equals("on") || s.equals("yes");
    }
}
