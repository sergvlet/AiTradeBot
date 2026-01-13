// src/main/java/com/chicu/aitradebot/web/advanced/VolumeProfileAdvancedRenderer.java
package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.volume.VolumeProfileStrategySettings;
import com.chicu.aitradebot.strategy.volume.VolumeProfileStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class VolumeProfileAdvancedRenderer implements StrategyAdvancedRenderer {

    private final VolumeProfileStrategySettingsService settingsService;

    @Override
    public StrategyType supports() {
        return StrategyType.VOLUME_PROFILE;
    }

    @Override
    public String render(AdvancedRenderContext ctx) {

        VolumeProfileStrategySettings s = settingsService.getOrCreate(ctx.getChatId());
        boolean ro = ctx.isReadOnly();

        String dis = ro ? " disabled" : "";
        String roAttr = ro ? " readonly" : "";

        String lookback = (s.getLookbackCandles() == null) ? "" : String.valueOf(s.getLookbackCandles());

        return ""
                + "<div class='card card-theme p-3 mb-3'>"
                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>VOLUME_PROFILE — параметры стратегии</div>"
                +      badge(ro)
                + "  </div>"
                + "  <div class='row g-3'>"

                + fieldNumber("lookbackCandles", "Lookback (свечи, опц.)", lookback,
                    "min='10' step='1'", dis, roAttr, "Если пусто — берётся из StrategySettings.cachedCandlesLimit (с капом).")

                + fieldNumber("bins", "Bins (корзины)", valInt(s.getBins()),
                    "min='8' step='1'", dis, roAttr, "Количество корзин гистограммы профиля.")

                + fieldNumber("valueAreaPct", "Value Area (%)", valBd(s.getValueAreaPct()),
                    "min='1' step='0.1'", dis, roAttr, "Например 70 = 70%.")

                + fieldSelectEntryMode(s.getEntryMode(), dis, roAttr)
                + "  </div>"
                + hint(ro)
                + "</div>";
    }

    @Override
    public void handleSubmit(AdvancedRenderContext ctx) {
        if (ctx.getControlMode() == AdvancedControlMode.AI) {
            log.info("🔒 VOLUME_PROFILE advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        VolumeProfileStrategySettings incoming = VolumeProfileStrategySettings.builder()
                .chatId(ctx.getChatId())
                .lookbackCandles(parseInt(p.get("lookbackCandles")))
                .bins(parseInt(p.get("bins")))
                .valueAreaPct(parseBd(p.get("valueAreaPct")))
                .entryMode(parseEntryMode(p.get("entryMode")))
                .build();

        settingsService.update(ctx.getChatId(), incoming);
        log.info("✅ VOLUME_PROFILE advanced saved (chatId={})", ctx.getChatId());
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
                + "<div class='col-md-3'>"
                + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <input type='number' class='form-control' name='" + HtmlUtils.htmlEscape(name) + "'"
                + "         value='" + safe + "' " + extraAttrs + dis + roAttr + ">"
                + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
                + "</div>";
    }

    private static String fieldSelectEntryMode(VolumeProfileStrategySettings.EntryMode cur, String dis, String roAttr) {
        String v = (cur == null) ? "MEAN_REVERT" : cur.name();
        String opt1 = option("MEAN_REVERT", "Mean Revert", v);
        String opt2 = option("BREAKOUT", "Breakout", v);

        return ""
                + "<div class='col-md-3'>"
                + "  <label class='form-label'>Entry mode</label>"
                + "  <select class='form-select' name='entryMode'" + dis + roAttr + ">"
                + opt1 + opt2
                + "  </select>"
                + "  <div class='form-text'>Режим входа от профиля объёма.</div>"
                + "</div>";
    }

    private static String option(String value, String label, String current) {
        String sel = value.equalsIgnoreCase(current) ? " selected" : "";
        return "<option value='" + HtmlUtils.htmlEscape(value) + "'" + sel + ">"
                + HtmlUtils.htmlEscape(label) + "</option>";
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

    private static VolumeProfileStrategySettings.EntryMode parseEntryMode(String v) {
        try {
            if (v == null || v.isBlank()) return null;
            return VolumeProfileStrategySettings.EntryMode.valueOf(v.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }
}
