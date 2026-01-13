package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.trend.TrendStrategySettings;
import com.chicu.aitradebot.strategy.trend.TrendStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrendAdvancedRenderer implements StrategyAdvancedRenderer {

    private final TrendStrategySettingsService settingsService;

    @Override
    public StrategyType supports() {
        return StrategyType.TREND;
    }

    @Override
    public String render(AdvancedRenderContext ctx) {

        TrendStrategySettings s = settingsService.getOrCreate(ctx.getChatId());

        boolean readOnly = ctx.isReadOnly();
        String dis = readOnly ? " disabled" : "";
        String ro  = readOnly ? " readonly" : "";

        return "<div class='card card-theme p-3 mb-3'>"

                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>TREND — параметры стратегии</div>"
                +      badge(readOnly)
                + "  </div>"

                + "  <div class='row g-3'>"

                + fieldNumber(
                    "emaFastPeriod",
                    "EMA fast (ticks)",
                    valInt(s.getEmaFastPeriod()),
                    "min='1' step='1'",
                    dis, ro,
                    "Быстрая EMA по тикам (на основе price update)."
                )

                + fieldNumber(
                    "emaSlowPeriod",
                    "EMA slow (ticks)",
                    valInt(s.getEmaSlowPeriod()),
                    "min='1' step='1'",
                    dis, ro,
                    "Медленная EMA по тикам (должна быть ≥ fast)."
                )

                + fieldText(
                    "trendThresholdPct",
                    "Trend threshold (%)",
                    valBd(s.getTrendThresholdPct()),
                    dis, ro,
                    "Минимальная разница EMA в %. Пример: 0.10 = 0.10%."
                )

                + fieldNumber(
                    "cooldownMs",
                    "Cooldown (ms)",
                    valInt(s.getCooldownMs()),
                    "min='0' step='50'",
                    dis, ro,
                    "Защита от “дребезга”: минимум миллисекунд между входами/выходами."
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
            log.info("🔒 TREND advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        TrendStrategySettings incoming = TrendStrategySettings.builder()
                .chatId(ctx.getChatId())
                .emaFastPeriod(parseInt(p.get("emaFastPeriod")))
                .emaSlowPeriod(parseInt(p.get("emaSlowPeriod")))
                .trendThresholdPct(parseBigDecimal(p.get("trendThresholdPct")))
                .cooldownMs(parseInt(p.get("cooldownMs")))
                .build();

        settingsService.update(ctx.getChatId(), incoming);
        log.info("✅ TREND advanced settings saved (chatId={})", ctx.getChatId());
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
        return "<div class='col-md-3'>"
                + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <input type='number' class='form-control' name='" + HtmlUtils.htmlEscape(name) + "'"
                + "         value='" + safe + "' " + extraAttrs + dis + ro + ">"
                + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
                + "</div>";
    }

    // threshold лучше как text (BigDecimal), чтобы не ловить locale/step проблемы
    private static String fieldText(
            String name,
            String label,
            String value,
            String dis,
            String ro,
            String help
    ) {
        String safe = value == null ? "" : HtmlUtils.htmlEscape(value);
        return "<div class='col-md-3'>"
                + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <input type='text' class='form-control' name='" + HtmlUtils.htmlEscape(name) + "'"
                + "         value='" + safe + "' placeholder='0.10' inputmode='decimal' " + dis + ro + ">"
                + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
                + "</div>";
    }

    private static String valInt(Integer v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String valBd(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static Integer parseInt(String v) {
        try {
            return v == null || v.isBlank() ? null : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal parseBigDecimal(String v) {
        try {
            if (v == null) return null;
            String s = v.trim();
            if (s.isEmpty()) return null;
            s = s.replace(",", ".");
            return new BigDecimal(s);
        } catch (Exception e) {
            return null;
        }
    }
}
