package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.windowscalping.WindowScalpingStrategySettings;
import com.chicu.aitradebot.strategy.windowscalping.WindowScalpingStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WindowScalpingAdvancedRenderer implements StrategyAdvancedRenderer {

    private final WindowScalpingStrategySettingsService settingsService;

    @Override
    public StrategyType supports() {
        return StrategyType.WINDOW_SCALPING;
    }

    @Override
    public String render(AdvancedRenderContext ctx) {

        WindowScalpingStrategySettings s = settingsService.getOrCreate(ctx.getChatId());

        boolean readOnly = ctx.isReadOnly();
        String dis = readOnly ? " disabled" : "";
        String ro  = readOnly ? " readonly" : "";

        return ""
                + "<div class='card card-theme p-3 mb-3'>"

                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>WINDOW_SCALPING — параметры стратегии</div>"
                +      badge(readOnly)
                + "  </div>"

                + "  <div class='row g-3'>"

                + fieldNumber(
                    "windowSize",
                    "Размер окна (bars)",
                    valInt(s.getWindowSize()),
                    "min='5' max='1000' step='1'",
                    dis, ro,
                    "Количество баров/тиков для High/Low окна."
                )

                + fieldNumber(
                    "entryFromLowPct",
                    "Вход у низа (%)",
                    valDbl(s.getEntryFromLowPct()),
                    "min='1' max='49' step='0.1'",
                    dis, ro,
                    "Например 20 = нижние 20% диапазона."
                )

                + fieldNumber(
                    "entryFromHighPct",
                    "Зона у верха (%)",
                    valDbl(s.getEntryFromHighPct()),
                    "min='1' max='49' step='0.1'",
                    dis, ro,
                    "Например 20 = верхние 20% диапазона."
                )

                + fieldNumber(
                    "minRangePct",
                    "Мин. диапазон окна (%)",
                    valDbl(s.getMinRangePct()),
                    "min='0.01' max='50' step='0.01'",
                    dis, ro,
                    "Если диапазон меньше — сделки блокируются."
                )

                + fieldNumber(
                    "maxSpreadPct",
                    "Макс. спред (%)",
                    valDbl(s.getMaxSpreadPct()),
                    "min='0' max='10' step='0.01'",
                    dis, ro,
                    "Опционально (на будущее). Сейчас может не использоваться."
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
            log.info("🔒 WINDOW_SCALPING advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        WindowScalpingStrategySettings incoming = WindowScalpingStrategySettings.builder()
                .chatId(ctx.getChatId())
                .windowSize(parseInt(p.get("windowSize")))
                .entryFromLowPct(parseDouble(p.get("entryFromLowPct")))
                .entryFromHighPct(parseDouble(p.get("entryFromHighPct")))
                .minRangePct(parseDouble(p.get("minRangePct")))
                .maxSpreadPct(parseDouble(p.get("maxSpreadPct")))
                .build();

        settingsService.update(ctx.getChatId(), incoming);
        log.info("✅ WINDOW_SCALPING advanced settings saved (chatId={})", ctx.getChatId());
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
