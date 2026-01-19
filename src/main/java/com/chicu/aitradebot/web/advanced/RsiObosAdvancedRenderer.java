package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.rsiobos.RsiObosStrategySettings;
import com.chicu.aitradebot.strategy.rsiobos.RsiObosStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RsiObosAdvancedRenderer implements StrategyAdvancedRenderer {

    private final RsiObosStrategySettingsService settingsService;

    @Override
    public StrategyType supports() {
        return StrategyType.RSI_OBOS;
    }

    @Override
    public String render(AdvancedRenderContext ctx) {

        RsiObosStrategySettings s = settingsService.getOrCreate(ctx.getChatId());

        boolean readOnly = ctx.isReadOnly();
        String dis = readOnly ? " disabled" : "";
        String ro  = readOnly ? " readonly" : "";

        return ""
                + "<div class='card card-theme p-3 mb-3'>"

                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>RSI_OBOS — параметры стратегии</div>"
                +      badge(readOnly)
                + "  </div>"

                + "  <div class='row g-3'>"

                + fieldNumber(
                    "rsiPeriod",
                    "RSI период",
                    valInt(s.getRsiPeriod()),
                    "min='2' max='200' step='1'",
                    dis, ro,
                    "Окно RSI. Обычно 14."
                )

                + fieldNumber(
                    "buyBelow",
                    "Buy when RSI ≤",
                    valDbl(s.getBuyBelow()),
                    "min='1' max='50' step='0.1'",
                    dis, ro,
                    "Порог перепроданности (например 30)."
                )

                + fieldNumber(
                    "blockAbove",
                    "Block when RSI ≥",
                    valDbl(s.getBlockAbove()),
                    "min='50' max='99' step='0.1'",
                    dis, ro,
                    "Фильтр перекупленности (например 70)."
                )

                + fieldToggle(
                    "spotLongOnly",
                    "Spot LONG only",
                    s.isSpotLongOnly(),
                    dis,
                    "На споте — только LONG. (Обычно true)"
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
            log.info("🔒 RSI_OBOS advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        // checkbox может прийти как "on"/"true"/"1" или отсутствовать
        boolean spotLongOnly = parseBool(p.get("spotLongOnly"));

        RsiObosStrategySettings incoming = RsiObosStrategySettings.builder()
                .chatId(ctx.getChatId())
                .rsiPeriod(parseInt(p.get("rsiPeriod")))
                .buyBelow(parseDouble(p.get("buyBelow")))
                .blockAbove(parseDouble(p.get("blockAbove")))
                .spotLongOnly(spotLongOnly)
                .build();

        settingsService.update(ctx.getChatId(), incoming);
        log.info("✅ RSI_OBOS advanced settings saved (chatId={})", ctx.getChatId());
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
        return ""
                + "<div class='col-md-3'>"
                + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <input type='number' class='form-control' name='" + HtmlUtils.htmlEscape(name) + "'"
                + "         value='" + safe + "' " + extraAttrs + dis + ro + ">"
                + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
                + "</div>";
    }

    private static String fieldToggle(
            String name,
            String label,
            boolean checked,
            String dis,
            String help
    ) {
        String ch = checked ? " checked" : "";
        return ""
                + "<div class='col-md-3'>"
                + "  <label class='form-label d-block'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <div class='form-check form-switch'>"
                + "    <input class='form-check-input' type='checkbox' name='" + HtmlUtils.htmlEscape(name) + "'" + ch + dis + ">"
                + "    <label class='form-check-label'>Включено</label>"
                + "  </div>"
                + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
                + "</div>";
    }

    private static String valInt(Integer v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String valDbl(Double v) {
        if (v == null) return "";
        // без scientific
        double x = v;
        if (Double.isNaN(x) || Double.isInfinite(x)) return "";
        String s = String.valueOf(x);
        // иногда "30.0" ок, оставляем
        return s;
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

    private static boolean parseBool(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase();
        return s.equals("1") || s.equals("true") || s.equals("on") || s.equals("yes");
    }
}
