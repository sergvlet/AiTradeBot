package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.trend_following.TrendFollowingStrategySettings;
import com.chicu.aitradebot.strategy.trend_following.TrendFollowingStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrendFollowingAdvancedRenderer implements StrategyAdvancedRenderer {

    private final TrendFollowingStrategySettingsService settingsService;

    @Override
    public StrategyType supports() {
        return StrategyType.TREND_FOLLOWING;
    }

    @Override
    public String render(AdvancedRenderContext ctx) {

        TrendFollowingStrategySettings s = settingsService.getOrCreate(ctx.getChatId());

        boolean readOnly = ctx.isReadOnly();
        String dis = readOnly ? " disabled" : "";
        String ro  = readOnly ? " readonly" : "";

        return "<div class='card card-theme p-3 mb-3'>"

                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>TREND_FOLLOWING — параметры стратегии</div>"
                +      badge(readOnly)
                + "  </div>"

                + "  <div class='row g-3'>"

                + fieldNumber(
                    "emaFast",
                    "EMA fast",
                    valInt(s.getEmaFast()),
                    "min='1' step='1'",
                    dis, ro,
                    "Быстрая EMA (короткая)."
                )

                + fieldNumber(
                    "emaSlow",
                    "EMA slow",
                    valInt(s.getEmaSlow()),
                    "min='1' step='1'",
                    dis, ro,
                    "Медленная EMA (должна быть ≥ fast)."
                )

                + fieldNumber(
                    "emaTrend",
                    "EMA trend",
                    valInt(s.getEmaTrend()),
                    "min='1' step='1'",
                    dis, ro,
                    "EMA глобального тренда (обычно 200, должна быть ≥ slow)."
                )

                + fieldNumber(
                    "minEmaDiffPct",
                    "Мин. дистанция fast/slow (%)",
                    valDouble(s.getMinEmaDiffPct()),
                    "min='0' step='0.01'",
                    dis, ro,
                    "Фильтр силы сигнала: насколько разошлись EMA."
                )

                + fieldNumber(
                    "minTrendSlopePct",
                    "Мин. наклон тренда (%)",
                    valDouble(s.getMinTrendSlopePct()),
                    "step='0.01'",
                    dis, ro,
                    "Наклон EMA trend. 0 = не требуем наклон. Можно отрицательное значение."
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
            log.info("🔒 TREND_FOLLOWING advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        TrendFollowingStrategySettings incoming = TrendFollowingStrategySettings.builder()
                .chatId(ctx.getChatId())
                .emaFast(parseInt(p.get("emaFast")))
                .emaSlow(parseInt(p.get("emaSlow")))
                .emaTrend(parseInt(p.get("emaTrend")))
                .minEmaDiffPct(parseDouble(p.get("minEmaDiffPct")))
                .minTrendSlopePct(parseDouble(p.get("minTrendSlopePct")))
                .build();

        settingsService.update(ctx.getChatId(), incoming);
        log.info("✅ TREND_FOLLOWING advanced settings saved (chatId={})", ctx.getChatId());
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
            return v == null || v.isBlank() ? null : Double.parseDouble(v.trim().replace(",", "."));
        } catch (Exception e) {
            return null;
        }
    }
}
