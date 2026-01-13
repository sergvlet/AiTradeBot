// src/main/java/com/chicu/aitradebot/web/advanced/VwapAdvancedRenderer.java
package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.vwap.VwapStrategySettings;
import com.chicu.aitradebot.strategy.vwap.VwapStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class VwapAdvancedRenderer implements StrategyAdvancedRenderer {

    private final VwapStrategySettingsService settingsService;

    @Override
    public StrategyType supports() {
        return StrategyType.VWAP;
    }

    @Override
    public String render(AdvancedRenderContext ctx) {

        VwapStrategySettings s = settingsService.getOrCreate(ctx.getChatId());
        boolean ro = ctx.isReadOnly();

        String dis = ro ? " disabled" : "";
        String roAttr = ro ? " readonly" : "";

        return ""
                + "<div class='card card-theme p-3 mb-3'>"
                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>VWAP — параметры стратегии</div>"
                +      badge(ro)
                + "  </div>"
                + "  <div class='row g-3'>"
                + fieldNumber("windowCandles", "Окно VWAP (свечи)", valInt(s.getWindowCandles()),
                    "min='5' step='1'", dis, roAttr, "Сколько свечей брать для VWAP.")
                + fieldNumber("entryDeviationPct", "Вход ниже VWAP (%)", valDouble(s.getEntryDeviationPct()),
                    "min='0' step='0.01'", dis, roAttr, "Вход, если цена ниже VWAP на X%.")
                + fieldNumber("exitDeviationPct", "Выход выше VWAP (%)", valDouble(s.getExitDeviationPct()),
                    "min='0' step='0.01'", dis, roAttr, "Выход, если цена выше VWAP на X%.")
                + "  </div>"
                + hint(ro)
                + "</div>";
    }

    @Override
    public void handleSubmit(AdvancedRenderContext ctx) {
        if (ctx.getControlMode() == AdvancedControlMode.AI) {
            log.info("🔒 VWAP advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        VwapStrategySettings incoming = VwapStrategySettings.builder()
                .chatId(ctx.getChatId())
                .windowCandles(parseInt(p.get("windowCandles")))
                .entryDeviationPct(parseDouble(p.get("entryDeviationPct")))
                .exitDeviationPct(parseDouble(p.get("exitDeviationPct")))
                .build();

        settingsService.update(ctx.getChatId(), incoming);
        log.info("✅ VWAP advanced saved (chatId={})", ctx.getChatId());
    }

    private static String badge(boolean ro) {
        return ro ? "<span class='badge bg-info'>AI</span>"
                  : "<span class='badge bg-secondary'>MANUAL / HYBRID</span>";
    }

    private static String hint(boolean ro) {
        return ro
                ? "<div class='alert alert-info small mt-3 mb-0'>Режим <b>AI</b>: ручное редактирование отключено.</div>"
                : "<div class='alert alert-secondary small mt-3 mb-0'>Режим <b>MANUAL / HYBRID</b>: параметры можно редактировать.</div>";
    }

    private static String fieldNumber(
            String name, String label, String value,
            String extraAttrs, String dis, String roAttr, String help
    ) {
        String safe = value == null ? "" : HtmlUtils.htmlEscape(value);
        return ""
                + "<div class='col-md-4'>"
                + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <input type='number' class='form-control' name='" + HtmlUtils.htmlEscape(name) + "'"
                + "         value='" + safe + "' " + extraAttrs + dis + roAttr + ">"
                + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
                + "</div>";
    }

    private static String valInt(Integer v) { return v == null ? "" : String.valueOf(v); }
    private static String valDouble(Double v) { return v == null ? "" : String.valueOf(v); }

    private static Integer parseInt(String v) {
        try { return (v == null || v.isBlank()) ? null : Integer.parseInt(v.trim()); }
        catch (Exception e) { return null; }
    }

    private static Double parseDouble(String v) {
        try { return (v == null || v.isBlank()) ? null : Double.parseDouble(v.trim()); }
        catch (Exception e) { return null; }
    }
}
