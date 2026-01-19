package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.momentum.MomentumStrategySettings;
import com.chicu.aitradebot.strategy.momentum.MomentumStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MomentumAdvancedRenderer implements StrategyAdvancedRenderer {

    private final MomentumStrategySettingsService momentumSettingsService;

    @Override
    public StrategyType supports() {
        return StrategyType.MOMENTUM;
    }

    // =====================================================
    // RENDER
    // =====================================================
    @Override
    public String render(AdvancedRenderContext ctx) {

        MomentumStrategySettings s = momentumSettingsService.getOrCreate(ctx.getChatId());

        boolean readOnly = ctx.isReadOnly();
        String dis = readOnly ? " disabled" : "";
        String roAttr = readOnly ? " readonly" : "";

        return ""
                + "<div class='card card-theme p-3 mb-3'>"

                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>MOMENTUM — параметры стратегии</div>"
                +      badge(readOnly)
                + "  </div>"

                + "  <div class='row g-3'>"

                + fieldNumber(
                    "lookbackBars",
                    "Окно импульса (bars)",
                    valInt(s.getLookbackBars()),
                    "min='1' step='1'",
                    dis,
                    roAttr,
                    "Сколько свечей анализируем для оценки импульса."
                )

                + fieldNumber(
                    "minPriceChangePct",
                    "Мин. изменение цены (%)",
                    valDouble(s.getMinPriceChangePct()),
                    "min='0' step='0.01'",
                    dis,
                    roAttr,
                    "Напр.: 0.6 означает +0.6% за окно."
                )

                + fieldNumber(
                    "volumeToAverage",
                    "Объём к среднему (x)",
                    valDouble(s.getVolumeToAverage()),
                    "min='0' step='0.1'",
                    dis,
                    roAttr,
                    "Фильтр по объёму (если подключишь объёмные данные)."
                )

                + fieldNumber(
                    "maxSpreadPct",
                    "Макс. спред (%)",
                    valDouble(s.getMaxSpreadPct()),
                    "min='0' step='0.01'",
                    dis,
                    roAttr,
                    "Фильтр по спреду (если будет источник спреда)."
                )

                + fieldNumber(
                    "confirmBars",
                    "Подтверждение (bars)",
                    valInt(s.getConfirmBars()),
                    "min='1' step='1'",
                    dis,
                    roAttr,
                    "Сколько баров подтверждения после сигнала."
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
    // SUBMIT
    // =====================================================
    @Override
    public void handleSubmit(AdvancedRenderContext ctx) {

        if (ctx.getControlMode() == AdvancedControlMode.AI) {
            log.info("🔒 MOMENTUM advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        MomentumStrategySettings incoming = MomentumStrategySettings.builder()
                .chatId(ctx.getChatId())
                .lookbackBars(parseInt(p.get("lookbackBars")))
                .minPriceChangePct(parseDouble(p.get("minPriceChangePct")))
                .volumeToAverage(parseDouble(p.get("volumeToAverage")))
                .maxSpreadPct(parseDouble(p.get("maxSpreadPct")))
                .confirmBars(parseInt(p.get("confirmBars")))
                .build();

        momentumSettingsService.update(ctx.getChatId(), incoming);
        log.info("✅ MOMENTUM advanced settings saved (chatId={})", ctx.getChatId());
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
            return v == null || v.isBlank() ? null : Double.parseDouble(v.trim().replace(",", "."));
        } catch (Exception e) {
            return null;
        }
    }
}
