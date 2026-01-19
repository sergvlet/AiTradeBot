package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.priceaction.PriceActionStrategySettings;
import com.chicu.aitradebot.strategy.priceaction.PriceActionStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriceActionAdvancedRenderer implements StrategyAdvancedRenderer {

    private final PriceActionStrategySettingsService settingsService;

    @Override
    public StrategyType supports() {
        return StrategyType.PRICE_ACTION;
    }

    @Override
    public String render(AdvancedRenderContext ctx) {

        PriceActionStrategySettings s = settingsService.getOrCreate(ctx.getChatId());

        boolean readOnly = ctx.isReadOnly(); // AI => true
        String dis = readOnly ? " disabled" : "";
        String ro  = readOnly ? " readonly" : "";

        return ""
                + "<div class='card card-theme p-3 mb-3'>"

                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>PRICE ACTION — параметры стратегии</div>"
                +      badge(ctx.getControlMode(), readOnly)
                + "  </div>"

                + "  <div class='row g-3'>"

                + fieldNumber(
                    "windowSize",
                    "Окно структуры (bars/ticks)",
                    valInt(s.getWindowSize()),
                    "min='20' max='20000' step='1'",
                    dis, ro,
                    "Окно для high/low и оценки структуры рынка."
                )

                + fieldNumber(
                    "minRangePct",
                    "Мин. диапазон окна (%)",
                    valDbl(s.getMinRangePct()),
                    "min='0' max='50' step='0.01'",
                    dis, ro,
                    "Если диапазон меньше — считаем флэт и не торгуем."
                )

                + fieldNumber(
                    "breakoutOfRangePct",
                    "Пробой от диапазона (%)",
                    valDbl(s.getBreakoutOfRangePct()),
                    "min='0' max='100' step='0.01'",
                    dis, ro,
                    "Сколько % от диапазона нужно, чтобы считать breakout структуры."
                )

                + fieldNumber(
                    "maxWickPctOfRange",
                    "Макс. wick от range (%)",
                    valDbl(s.getMaxWickPctOfRange()),
                    "min='0' max='100' step='0.1'",
                    dis, ro,
                    "Если тени свечей слишком большие — избегаем входа."
                )

                + fieldNumber(
                    "confirmTicks",
                    "Подтверждение (ticks)",
                    valInt(s.getConfirmTicks()),
                    "min='1' max='1000' step='1'",
                    dis, ro,
                    "Сколько тиков подряд цена удерживается за уровнем."
                )

                + fieldCheckbox(
                    "enabled",
                    "Стратегия включена",
                    s.isEnabled(),
                    dis,
                    "Выключает/включает логику входов этой стратегии."
                )

                + "  </div>"

                + note(readOnly)

                + "</div>";
    }

    @Override
    public void handleSubmit(AdvancedRenderContext ctx) {

        if (ctx.getControlMode() == AdvancedControlMode.AI) {
            log.info("🔒 PRICE_ACTION advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        PriceActionStrategySettings incoming = PriceActionStrategySettings.builder()
                .chatId(ctx.getChatId())
                .windowSize(parseInt(p.get("windowSize")))
                .minRangePct(parseDouble(p.get("minRangePct")))
                .breakoutOfRangePct(parseDouble(p.get("breakoutOfRangePct")))
                .maxWickPctOfRange(parseDouble(p.get("maxWickPctOfRange")))
                .confirmTicks(parseInt(p.get("confirmTicks")))
                .enabled(parseBool(p.get("enabled")))
                .build();

        settingsService.update(ctx.getChatId(), incoming);
        log.info("✅ PRICE_ACTION advanced settings saved (chatId={})", ctx.getChatId());
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private static String badge(AdvancedControlMode mode, boolean ro) {
        String text = (mode == null) ? "—" : mode.name();
        String cls = ro ? "bg-info" : "bg-secondary";
        return "<span class='badge " + cls + "'>" + HtmlUtils.htmlEscape(text) + "</span>";
    }

    private static String note(boolean ro) {
        return ro
                ? "<div class='alert alert-info small mt-3 mb-0'>Режим <b>AI</b>: ручное редактирование отключено.</div>"
                : "<div class='alert alert-secondary small mt-3 mb-0'>Режим <b>MANUAL / HYBRID</b>: параметры можно редактировать.</div>";
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
                + "<div class='col-md-4'>"
                + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <input type='number' class='form-control' name='" + HtmlUtils.htmlEscape(name) + "'"
                + "         value='" + safe + "' " + extraAttrs + dis + ro + ">"
                + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
                + "</div>";
    }

    private static String fieldCheckbox(String name, String label, boolean checked, String dis, String help) {
        return ""
                + "<div class='col-md-4'>"
                + "  <label class='form-label d-block'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <div class='form-check form-switch'>"
                + "    <input class='form-check-input' type='checkbox' name='" + HtmlUtils.htmlEscape(name) + "'"
                +        (checked ? " checked" : "") + dis + ">"
                + "    <label class='form-check-label'>"
                +        (checked ? "Включено" : "Выключено")
                + "    </label>"
                + "  </div>"
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
            return (v == null || v.isBlank()) ? null : Integer.parseInt(v.trim());
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
        return s.equals("true") || s.equals("1") || s.equals("on") || s.equals("yes");
    }
}
