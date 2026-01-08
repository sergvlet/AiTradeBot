package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettings;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScalpingAdvancedRenderer implements StrategyAdvancedRenderer {

    private final ScalpingStrategySettingsService scalpingSettingsService;

    @Override
    public StrategyType supports() {
        return StrategyType.SCALPING;
    }

    // =====================================================
    // RENDER
    // =====================================================
    @Override
    public String render(AdvancedRenderContext ctx) {

        ScalpingStrategySettings s =
                scalpingSettingsService.getOrCreate(ctx.getChatId());

        boolean readOnly = ctx.isReadOnly();

        String dis    = readOnly ? " disabled" : "";
        String roAttr = readOnly ? " readonly" : "";

        return ""
               + "<div class='card card-theme p-3 mb-3'>"

               + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
               + "    <div class='fw-bold'>SCALPING — параметры стратегии</div>"
               +      badge(readOnly)
               + "  </div>"

               + "  <div class='row g-3'>"

               + fieldNumber(
                "windowSize",
                "Окно анализа",
                valInt(s.getWindowSize()),
                "min='1' step='1'",
                dis,
                roAttr,
                "Количество последних свечей для анализа."
        )

               + fieldNumber(
                "priceChangeThreshold",
                "Порог движения (%)",
                valDouble(s.getPriceChangeThreshold()),
                "min='0' step='0.01'",
                dis,
                roAttr,
                "Минимальное изменение цены для входа."
        )

               + fieldNumber(
                "spreadThreshold",
                "Максимальный спред (%)",
                valDouble(s.getSpreadThreshold()),
                "min='0' step='0.01'",
                dis,
                roAttr,
                "Если спред выше — вход запрещён."
        )

               + "  </div>"

               + (readOnly
                ? "<div class='alert alert-info small mt-3 mb-0'>"
                  + "Режим <b>AI</b>: параметры управляются автоматически."
                  + "</div>"
                : "<div class='alert alert-secondary small mt-3 mb-0'>"
                  + "Режим <b>MANUAL / HYBRID</b>: параметры можно редактировать."
                  + "</div>"
               )

               + "</div>";
    }

    // =====================================================
    // SUBMIT (🔥 ВАЖНО)
    // =====================================================
    @Override
    public void handleSubmit(AdvancedRenderContext ctx) {

        // если AI — не трогаем ручные параметры
        if (ctx.getControlMode() == AdvancedControlMode.AI) {
            log.info("🔒 SCALPING advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        ScalpingStrategySettings incoming = ScalpingStrategySettings.builder()
                .chatId(ctx.getChatId())
                .windowSize(parseInt(p.get("windowSize")))
                .priceChangeThreshold(parseDouble(p.get("priceChangeThreshold")))
                .spreadThreshold(parseDouble(p.get("spreadThreshold")))
                .build();

        scalpingSettingsService.update(ctx.getChatId(), incoming);

        log.info("✅ SCALPING advanced settings saved (chatId={})", ctx.getChatId());
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
            String roAttr,
            String help
    ) {
        String safe = value == null ? "" : HtmlUtils.htmlEscape(value);

        return ""
               + "<div class='col-md-3'>"
               + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
               + "  <input type='number' class='form-control' name='" + HtmlUtils.htmlEscape(name) + "'"
               + "         value='" + safe + "' " + extraAttrs + dis + roAttr + ">"
               + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
               + "</div>";
    }

    private static String valInt(Integer v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String valDouble(Double v) {
        return v == null ? "" : String.valueOf(v);
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
            return v == null || v.isBlank() ? null : Double.parseDouble(v.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
