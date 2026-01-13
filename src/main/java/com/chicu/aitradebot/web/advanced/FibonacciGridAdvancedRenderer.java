// src/main/java/com/chicu/aitradebot/web/advanced/FibonacciGridAdvancedRenderer.java
package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.fibonacci_grid.FibonacciGridStrategySettings;
import com.chicu.aitradebot.strategy.fibonacci_grid.FibonacciGridStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class FibonacciGridAdvancedRenderer implements StrategyAdvancedRenderer {

    private final FibonacciGridStrategySettingsService settingsService;

    @Override
    public StrategyType supports() {
        return StrategyType.FIBONACCI_GRID;
    }

    @Override
    public String render(AdvancedRenderContext ctx) {

        FibonacciGridStrategySettings s = settingsService.getOrCreate(ctx.getChatId());
        boolean ro = ctx.isReadOnly();

        String dis = ro ? " disabled" : "";
        String roAttr = ro ? " readonly" : "";

        return ""
                + "<div class='card card-theme p-3 mb-3'>"
                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>FIBONACCI_GRID — параметры стратегии</div>"
                +      badge(ro)
                + "  </div>"
                + "  <div class='row g-3'>"
                + fieldNumber("gridLevels", "Уровни сетки", valInt(s.getGridLevels()),
                    "min='1' step='1'", dis, roAttr, "Количество уровней (покупки вниз от базовой цены).")
                + fieldNumber("distancePct", "Дистанция (%)", valBd(s.getDistancePct()),
                    "min='0' step='0.01'", dis, roAttr, "Шаг между уровнями в процентах.")
                + fieldNumber("orderVolume", "Объём ордера (опц.)", valBd(s.getOrderVolume()),
                    "min='0' step='0.00000001'", dis, roAttr, "Если пусто — объём берётся из глобальных/исполнителя.")
                + "  </div>"
                + hint(ro)
                + "</div>";
    }

    @Override
    public void handleSubmit(AdvancedRenderContext ctx) {
        if (ctx.getControlMode() == AdvancedControlMode.AI) {
            log.info("🔒 FIBONACCI_GRID advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        FibonacciGridStrategySettings incoming = FibonacciGridStrategySettings.builder()
                .chatId(ctx.getChatId())
                .gridLevels(parseInt(p.get("gridLevels")))
                .distancePct(parseBd(p.get("distancePct")))
                .orderVolume(parseBd(p.get("orderVolume"))) // допускаем null
                .build();

        settingsService.update(ctx.getChatId(), incoming);
        log.info("✅ FIBONACCI_GRID advanced saved (chatId={})", ctx.getChatId());
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
