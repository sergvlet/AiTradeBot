package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.windowscalping.WindowScalpingStrategySettings;
import com.chicu.aitradebot.strategy.windowscalping.WindowScalpingStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
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

        String dis = readOnly ? " disabled" : "";
        String roAttr = readOnly ? " readonly" : "";

        return "<div class='card card-theme p-3 mb-3'>"

                + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
                + "    <div class='fw-bold'>WINDOW_SCALPING — параметры стратегии</div>"
                + badge(readOnly)
                + "  </div>"

                + "  <div class='row g-3'>"

                + sectionTitle("Fallback TP / SL")
                + fieldNumber(
                        "takeProfitPct",
                        "Take Profit (%)",
                        valBd(s.getTakeProfitPct()),
                        "min='0' step='0.01'",
                        dis,
                        roAttr,
                        "Статический TP, если auto TP/SL выключен или динамический расчёт не сработал."
                )
                + fieldNumber(
                        "stopLossPct",
                        "Stop Loss (%)",
                        valBd(s.getStopLossPct()),
                        "min='0' step='0.01'",
                        dis,
                        roAttr,
                        "Статический SL, если auto TP/SL выключен или динамический расчёт не сработал."
                )

                + sectionTitle("Авто TP / SL")
                + fieldBooleanSelect(
                        "autoTpSlEnabled",
                        "Авто TP/SL",
                        s.getAutoTpSlEnabled(),
                        dis,
                        "Автоподстройка TP/SL под текущий диапазон окна."
                )
                + fieldNumber(
                        "autoSlFromRangeFactor",
                        "SL factor от range",
                        valBd(s.getAutoSlFromRangeFactor()),
                        "min='0' step='0.01'",
                        dis,
                        roAttr,
                        "SL = rangePct × autoSlFromRangeFactor."
                )
                + fieldNumber(
                        "autoTpFromRangeFactor",
                        "TP factor от range",
                        valBd(s.getAutoTpFromRangeFactor()),
                        "min='0' step='0.01'",
                        dis,
                        roAttr,
                        "TP = max(rangePct × autoTpFromRangeFactor, SL × min RR)."
                )
                + fieldNumber(
                        "autoMinRiskReward",
                        "Мин. RR",
                        valBd(s.getAutoMinRiskReward()),
                        "min='0' step='0.01'",
                        dis,
                        roAttr,
                        "Минимальное отношение TP/SL для динамического расчёта."
                )
                + fieldNumber(
                        "autoSlMinPct",
                        "Мин. auto SL (%)",
                        valBd(s.getAutoSlMinPct()),
                        "min='0' step='0.01'",
                        dis,
                        roAttr,
                        "Нижняя граница динамического SL."
                )
                + fieldNumber(
                        "autoSlMaxPct",
                        "Макс. auto SL (%)",
                        valBd(s.getAutoSlMaxPct()),
                        "min='0' step='0.01'",
                        dis,
                        roAttr,
                        "Верхняя граница динамического SL."
                )
                + fieldNumber(
                        "autoTpMinPct",
                        "Мин. auto TP (%)",
                        valBd(s.getAutoTpMinPct()),
                        "min='0' step='0.01'",
                        dis,
                        roAttr,
                        "Нижняя граница динамического TP."
                )
                + fieldNumber(
                        "autoTpMaxPct",
                        "Макс. auto TP (%)",
                        valBd(s.getAutoTpMaxPct()),
                        "min='0' step='0.01'",
                        dis,
                        roAttr,
                        "Верхняя граница динамического TP."
                )
                + fieldNumber(
                        "autoTpMlBoostFactor",
                        "ML boost factor",
                        valBd(s.getAutoTpMlBoostFactor()),
                        "min='0' step='0.01'",
                        dis,
                        roAttr,
                        "Мультипликатор TP для сильного ML-сигнала."
                )
                + fieldNumber(
                        "autoTpWeakSignalFactor",
                        "Weak signal factor",
                        valBd(s.getAutoTpWeakSignalFactor()),
                        "min='0' step='0.01'",
                        dis,
                        roAttr,
                        "Ослабление TP для слабого сигнала около порога."
                )

                + sectionTitle("Параметры окна")
                + fieldNumber(
                        "windowSize",
                        "Размер окна",
                        valInt(s.getWindowSize()),
                        "min='5' step='1'",
                        dis,
                        roAttr,
                        "Количество тиков/баров для high/low окна."
                )
                + fieldNumber(
                        "entryFromLowPct",
                        "Вход от низа (%)",
                        valDouble(s.getEntryFromLowPct()),
                        "min='0' max='100' step='0.01'",
                        dis,
                        roAttr,
                        "Вход в нижних X% диапазона окна."
                )
                + fieldNumber(
                        "entryFromHighPct",
                        "Зона у верха (%)",
                        valDouble(s.getEntryFromHighPct()),
                        "min='0' max='100' step='0.01'",
                        dis,
                        roAttr,
                        "Верхние X% диапазона окна."
                )
                + fieldNumber(
                        "minRangePct",
                        "Мин. ширина диапазона (%)",
                        valDouble(s.getMinRangePct()),
                        "min='0' step='0.01'",
                        dis,
                        roAttr,
                        "Если окно слишком узкое — вход запрещён."
                )
                + fieldNumber(
                        "maxSpreadPct",
                        "Макс. спред (%)",
                        valDouble(s.getMaxSpreadPct()),
                        "min='0' step='0.01'",
                        dis,
                        roAttr,
                        "Ограничение по спреду."
                )

                + "  </div>"

                + (readOnly
                ? "<div class='alert alert-info small mt-3 mb-0'>Режим <b>AI</b>: ручное изменение advanced-полей заблокировано.</div>"
                : "<div class='alert alert-secondary small mt-3 mb-0'>Режим <b>MANUAL / HYBRID</b>: все advanced-поля можно сохранять вручную.</div>"
                )

                + "</div>";
    }

    @Override
    public void handleSubmit(AdvancedRenderContext ctx) {

        if (ctx.getControlMode() == AdvancedControlMode.AI) {
            log.info("🔒 WINDOW_SCALPING advanced ignored (AI mode, chatId={})", ctx.getChatId());
            return;
        }

        Map<String, String> p = ctx.getParams();

        WindowScalpingStrategySettings incoming = WindowScalpingStrategySettings.builder()
                .chatId(ctx.getChatId())

                // fallback TP/SL
                .takeProfitPct(parseBigDecimal(p.get("takeProfitPct")))
                .stopLossPct(parseBigDecimal(p.get("stopLossPct")))

                // auto TP/SL
                .autoTpSlEnabled(parseBoolean(p.get("autoTpSlEnabled")))
                .autoSlFromRangeFactor(parseBigDecimal(p.get("autoSlFromRangeFactor")))
                .autoTpFromRangeFactor(parseBigDecimal(p.get("autoTpFromRangeFactor")))
                .autoMinRiskReward(parseBigDecimal(p.get("autoMinRiskReward")))
                .autoSlMinPct(parseBigDecimal(p.get("autoSlMinPct")))
                .autoSlMaxPct(parseBigDecimal(p.get("autoSlMaxPct")))
                .autoTpMinPct(parseBigDecimal(p.get("autoTpMinPct")))
                .autoTpMaxPct(parseBigDecimal(p.get("autoTpMaxPct")))
                .autoTpMlBoostFactor(parseBigDecimal(p.get("autoTpMlBoostFactor")))
                .autoTpWeakSignalFactor(parseBigDecimal(p.get("autoTpWeakSignalFactor")))

                // window
                .windowSize(parseInt(p.get("windowSize")))
                .entryFromLowPct(parseDouble(p.get("entryFromLowPct")))
                .entryFromHighPct(parseDouble(p.get("entryFromHighPct")))
                .minRangePct(parseDouble(p.get("minRangePct")))
                .maxSpreadPct(parseDouble(p.get("maxSpreadPct")))
                .build();

        settingsService.update(ctx.getChatId(), incoming);

        log.info(
                "✅ WINDOW_SCALPING advanced settings saved (chatId={}, autoTpSlEnabled={}, slFactor={}, tpFactor={}, minRR={}, slMin={}, slMax={}, tpMin={}, tpMax={}, mlBoost={}, weakFactor={}, tpPct={}, slPct={}, windowSize={}, entryLowPct={}, entryHighPct={}, minRangePct={}, maxSpreadPct={})",
                ctx.getChatId(),
                incoming.getAutoTpSlEnabled(),
                incoming.getAutoSlFromRangeFactor(),
                incoming.getAutoTpFromRangeFactor(),
                incoming.getAutoMinRiskReward(),
                incoming.getAutoSlMinPct(),
                incoming.getAutoSlMaxPct(),
                incoming.getAutoTpMinPct(),
                incoming.getAutoTpMaxPct(),
                incoming.getAutoTpMlBoostFactor(),
                incoming.getAutoTpWeakSignalFactor(),
                incoming.getTakeProfitPct(),
                incoming.getStopLossPct(),
                incoming.getWindowSize(),
                incoming.getEntryFromLowPct(),
                incoming.getEntryFromHighPct(),
                incoming.getMinRangePct(),
                incoming.getMaxSpreadPct()
        );
    }

    private static String badge(boolean ro) {
        return ro
                ? "<span class='badge bg-info'>AI</span>"
                : "<span class='badge bg-secondary'>MANUAL / HYBRID</span>";
    }

    private static String sectionTitle(String title) {
        return "<div class='col-12 mt-2'>"
                + "  <div class='fw-semibold border-top pt-2'>"
                + HtmlUtils.htmlEscape(title)
                + "  </div>"
                + "</div>";
    }

    private static String fieldBooleanSelect(
            String name,
            String label,
            Boolean value,
            String dis,
            String help
    ) {
        boolean enabled = value == null || Boolean.TRUE.equals(value);

        return "<div class='col-md-3'>"
                + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <select class='form-select' name='" + HtmlUtils.htmlEscape(name) + "'" + dis + ">"
                + option("true", "Включено", enabled)
                + option("false", "Выключено", !enabled)
                + "  </select>"
                + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
                + "</div>";
    }

    private static String option(String value, String label, boolean selected) {
        return "<option value='" + HtmlUtils.htmlEscape(value) + "'" + (selected ? " selected" : "") + ">"
                + HtmlUtils.htmlEscape(label)
                + "</option>";
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

    private static String valInt(Integer v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String valDouble(Double v) {
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

    private static Double parseDouble(String v) {
        try {
            if (v == null || v.isBlank()) return null;
            String s = v.trim().replace(",", ".");
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal parseBigDecimal(String v) {
        try {
            if (v == null || v.isBlank()) return null;
            String s = v.trim().replace(",", ".");
            return new BigDecimal(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean parseBoolean(String v) {
        if (v == null || v.isBlank()) return null;

        String s = v.trim().toLowerCase();
        return switch (s) {
            case "true", "1", "yes", "y", "on" -> Boolean.TRUE;
            case "false", "0", "no", "n", "off" -> Boolean.FALSE;
            default -> null;
        };
    }
}