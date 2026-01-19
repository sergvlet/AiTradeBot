// src/main/java/com/chicu/aitradebot/web/advanced/DcaAdvancedRenderer.java
package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.dca.DcaStrategySettings;
import com.chicu.aitradebot.strategy.dca.DcaStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DcaAdvancedRenderer implements StrategyAdvancedRenderer {

    private final DcaStrategySettingsService service;

    @Override
    public StrategyType supports() {
        return StrategyType.DCA;
    }

    @Override
    public String render(AdvancedRenderContext ctx) {
        DcaStrategySettings s = service.getOrCreate(ctx.getChatId());

        boolean ro = ctx.isReadOnly();
        String dis = ro ? " disabled" : "";
        String roAttr = ro ? " readonly" : "";

        return ""
                + "<div class='card card-theme p-3 mb-3'>"
                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>DCA — параметры стратегии</div>"
                + badge(ro)
                + "  </div>"

                + "  <div class='row g-3'>"

                + fieldNumber("intervalMinutes", "Интервал (мин)", valI(s.getIntervalMinutes()),
                    "min='1' step='1'", dis, roAttr, "Например 60 = раз в час.")
                + fieldBd("orderVolume", "Объём покупки", valBd(s.getOrderVolume()),
                    "min='0' step='0.00000001'", dis, roAttr, "В валюте котировки (обычно USDT).")

                + fieldBd("takeProfitPct", "TP (%) (опц.)", valBd(s.getTakeProfitPct()),
                    "min='0' step='0.01'", dis, roAttr, "Если пусто — берётся из StrategySettings/дефолтов.")
                + fieldBd("stopLossPct", "SL (%) (опц.)", valBd(s.getStopLossPct()),
                    "min='0' step='0.01'", dis, roAttr, "Если пусто — берётся из StrategySettings/дефолтов.")

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
            log.info("🔒 DCA advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        DcaStrategySettings in = DcaStrategySettings.builder()
                .chatId(ctx.getChatId())
                .intervalMinutes(parseInt(p.get("intervalMinutes")))
                .orderVolume(parseBd(p.get("orderVolume")))
                .takeProfitPct(parseBd(p.get("takeProfitPct")))
                .stopLossPct(parseBd(p.get("stopLossPct")))
                .build();

        service.update(ctx.getChatId(), in);
        log.info("✅ DCA advanced settings saved (chatId={})", ctx.getChatId());
    }

    // ================= helpers =================

    private static String badge(boolean ro) {
        return ro ? "<span class='badge bg-info'>AI</span>"
                  : "<span class='badge bg-secondary'>MANUAL / HYBRID</span>";
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
}
