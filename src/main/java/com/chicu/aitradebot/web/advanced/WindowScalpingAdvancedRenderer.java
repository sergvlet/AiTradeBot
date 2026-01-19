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

        boolean canEdit = ctx.canSubmit();
        boolean readOnly = !canEdit;

        String dis    = readOnly ? " disabled" : "";
        String roAttr = readOnly ? " readonly" : "";

        return "<div class='card card-theme p-3 mb-3'>"

                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>WINDOW_SCALPING — параметры стратегии</div>"
                +      badge(readOnly)
                + "  </div>"

                + "  <div class='row g-3'>"

                + fieldNumber("windowSize", "Размер окна", valInt(s.getWindowSize()),
                "min='1' step='1'", dis, roAttr, "Кол-во тиков/баров для high/low окна.")

                + fieldNumber("entryFromLowPct", "Вход от низа (%)", valDouble(s.getEntryFromLowPct()),
                "min='0' step='0.01'", dis, roAttr, "Вход в нижних X% диапазона окна.")

                + fieldNumber("entryFromHighPct", "Зона у верха (%)", valDouble(s.getEntryFromHighPct()),
                "min='0' step='0.01'", dis, roAttr, "Верхние X% диапазона окна.")

                + fieldNumber("minRangePct", "Мин. ширина диапазона (%)", valDouble(s.getMinRangePct()),
                "min='0' step='0.01'", dis, roAttr, "Если окно слишком узкое — не торгуем.")

                + fieldNumber("maxSpreadPct", "Макс. спред (%)", valDouble(s.getMaxSpreadPct()),
                "min='0' step='0.01'", dis, roAttr, "Поле на будущее (если появится источник спреда).")

                + "  </div>"

                + (readOnly
                ? "<div class='alert alert-info small mt-3 mb-0'>Режим <b>AI</b>: параметры управляются автоматически.</div>"
                : "<div class='alert alert-secondary small mt-3 mb-0'>Режим <b>MANUAL / HYBRID</b>: параметры можно редактировать.</div>"
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
            String roAttr,
            String help
    ) {
        String safe = value == null ? "" : HtmlUtils.htmlEscape(value);

        return "<div class='col-md-3'>"
                + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <input type='number' class='form-control' name='" + HtmlUtils.htmlEscape(name) + "'"
                + "         value='" + safe + "' " + extraAttrs + dis + roAttr + ">"
                + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
                + "</div>";
    }

    private static String valInt(Integer v) { return v == null ? "" : String.valueOf(v); }
    private static String valDouble(Double v) { return v == null ? "" : String.valueOf(v); }

    private static Integer parseInt(String v) {
        try { return v == null || v.isBlank() ? null : Integer.parseInt(v.trim()); }
        catch (Exception e) { return null; }
    }

    private static Double parseDouble(String v) {
        try {
            if (v == null || v.isBlank()) return null;
            String s = v.trim().replace(",", ".");
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }
}
