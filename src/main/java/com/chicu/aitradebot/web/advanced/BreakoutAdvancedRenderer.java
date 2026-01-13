package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.breakout.BreakoutStrategySettings;
import com.chicu.aitradebot.strategy.breakout.BreakoutStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BreakoutAdvancedRenderer implements StrategyAdvancedRenderer {

    private final BreakoutStrategySettingsService settingsService;

    @Override
    public StrategyType supports() {
        return StrategyType.BREAKOUT;
    }

    @Override
    public String render(AdvancedRenderContext ctx) {

        BreakoutStrategySettings s = settingsService.getOrCreate(ctx.getChatId());

        boolean readOnly = ctx.isReadOnly();
        String dis = readOnly ? " disabled" : "";
        String ro  = readOnly ? " readonly" : "";

        return "<div class='card card-theme p-3 mb-3'>"

                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>BREAKOUT — параметры стратегии</div>"
                +      badge(readOnly)
                + "  </div>"

                + "  <div class='row g-3'>"

                + fieldNumber(
                    "rangeLookback",
                    "Окно диапазона (bars)",
                    valInt(s.getRangeLookback()),
                    "min='5' max='2000' step='1'",
                    dis, ro,
                    "Сколько свечей берём для диапазона High/Low."
                )

                + fieldNumber(
                    "breakoutBufferPct",
                    "Буфер пробоя (%)",
                    valDbl(s.getBreakoutBufferPct()),
                    "min='0' max='10' step='0.01'",
                    dis, ro,
                    "Буфер над high (или под low), чтобы не ловить микропрокол."
                )

                + fieldNumber(
                    "minRangePct",
                    "Мин. ширина диапазона (%)",
                    valDbl(s.getMinRangePct()),
                    "min='0.01' max='50' step='0.01'",
                    dis, ro,
                    "Если диапазон слишком узкий — сделок нет."
                )

                + "  </div>"

                + (readOnly
                    ? "<div class='alert alert-info small mt-3 mb-0'>"
                      + "Режим <b>AI</b>: ручное редактирование отключено."
                      + "</div>"
                    : "<div class='alert alert-secondary small mt-3 mb-0'>"
                      + "Режим <b>MANUAL / HYBRID</b>: параметры можно редактировать."
                      + "</div>"
                )

                + "</div>";
    }

    @Override
    public void handleSubmit(AdvancedRenderContext ctx) {

        if (ctx.getControlMode() == AdvancedControlMode.AI) {
            log.info("🔒 BREAKOUT advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        BreakoutStrategySettings incoming = BreakoutStrategySettings.builder()
                .chatId(ctx.getChatId())
                .rangeLookback(parseInt(p.get("rangeLookback")))
                .breakoutBufferPct(parseDouble(p.get("breakoutBufferPct")))
                .minRangePct(parseDouble(p.get("minRangePct")))
                .build();

        settingsService.update(ctx.getChatId(), incoming);
        log.info("✅ BREAKOUT advanced settings saved (chatId={})", ctx.getChatId());
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private static String badge(boolean ro) {
        return ro
                ? "<span class='badge bg-info'>AI</span>"
                : "<span class='badge bg-secondary'>MANUAL / HYBRID</span>";
    }

    private static String fieldNumber(
            String name,
            String label,
            String value,
            String extraAttrs,
            String dis,
            String ro,
            String help
    ) {
        String safe = value == null ? "" : HtmlUtils.htmlEscape(value);
        return "<div class='col-md-4'>"
                + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <input type='number' class='form-control' name='" + HtmlUtils.htmlEscape(name) + "'"
                + "         value='" + safe + "' " + extraAttrs + dis + ro + ">"
                + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
                + "</div>";
    }

    private static String valInt(Integer v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String valDbl(Double v) {
        if (v == null) return "";
        double x = v;
        if (Double.isNaN(x) || Double.isInfinite(x)) return "";
        return String.valueOf(x);
    }

    private static Integer parseInt(String v) {
        try {
            return v == null || v.isBlank() ? null : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Double parseDouble(String v) {
        try {
            if (v == null) return null;
            String s = v.trim();
            if (s.isEmpty()) return null;
            s = s.replace(",", ".");
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }
}
