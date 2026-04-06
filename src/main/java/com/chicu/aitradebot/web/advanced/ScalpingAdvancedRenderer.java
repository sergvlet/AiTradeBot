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

    @Override
    public String render(AdvancedRenderContext ctx) {
        ScalpingStrategySettings s = scalpingSettingsService.getOrCreate(ctx.getChatId());

        boolean readOnly = ctx.isReadOnly();
        String dis = readOnly ? " disabled" : "";
        String roAttr = readOnly ? " readonly" : "";

        return "<div class='card card-theme p-3 mb-3'>"
               + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
               + "    <div class='fw-bold'>SCALPING V4 — параметры стратегии</div>"
               +      badge(readOnly)
               + "  </div>"

               + "  <div class='row g-3'>"

               + fieldNumber(
                "windowSize",
                "Окно",
                valInt(s.getWindowSize()),
                "min='5' step='1'",
                dis,
                roAttr,
                "Размер рабочего окна для core-расчётов стратегии."
        )

               + fieldNumber(
                "minImpulsePct",
                "Мин. импульс (%)",
                valDouble(s.getMinImpulsePct()),
                "min='0' step='0.01'",
                dis,
                roAttr,
                "Минимальное изменение цены для входа."
        )

               + fieldNumber(
                "emaDiffThreshold",
                "Сила тренда EMA (%)",
                valDouble(s.getEmaDiffThreshold()),
                "min='0' step='0.01'",
                dis,
                roAttr,
                "Минимальный разрыв между EMA fast и EMA slow."
        )

               + fieldNumber(
                "volumeRatio",
                "Отношение объёма",
                valDouble(s.getVolumeRatio()),
                "min='0' step='0.01'",
                dis,
                roAttr,
                "Минимальное отношение текущей активности к средней."
        )

               + fieldNumber(
                "spreadLimitPct",
                "Макс. спред (%)",
                valDouble(s.getSpreadLimitPct()),
                "min='0' step='0.01'",
                dis,
                roAttr,
                "Если спред выше — вход запрещён."
        )

               + fieldNumber(
                "atrPctRange",
                "ATR диапазон (%)",
                valDouble(s.getAtrPctRange()),
                "min='0' step='0.01'",
                dis,
                roAttr,
                "Максимально допустимая краткосрочная волатильность."
        )

               + fieldNumber(
                "rsiFilter",
                "RSI фильтр",
                valDouble(s.getRsiFilter()),
                "min='1' max='99' step='0.1'",
                dis,
                roAttr,
                "Минимальное значение RSI для long-входа."
        )

               + fieldNumber(
                "riskRewardMin",
                "Мин. R/R",
                valDouble(s.getRiskRewardMin()),
                "min='0.1' step='0.01'",
                dis,
                roAttr,
                "Минимально допустимое отношение reward/risk."
        )

               + fieldNumber(
                "orderVolume",
                "Объём ордера",
                valDouble(s.getOrderVolume()),
                "min='0.0001' step='0.01'",
                dis,
                roAttr,
                "Размер входа для исполнения сделки."
        )

               + fieldNumber(
                "takeProfitPct",
                "Take Profit (%)",
                valDouble(s.getTakeProfitPct()),
                "min='0.01' step='0.01'",
                dis,
                roAttr,
                "Целевой take profit."
        )

               + fieldNumber(
                "stopLossPct",
                "Stop Loss (%)",
                valDouble(s.getStopLossPct()),
                "min='0.01' step='0.01'",
                dis,
                roAttr,
                "Защитный stop loss."
        )

               + fieldText(
                "symbol",
                "Символ",
                valText(s.getSymbol()),
                dis,
                roAttr,
                "Торговый символ, например BTCUSDT."
        )

               + fieldText(
                "timeframe",
                "Таймфрейм",
                valText(s.getTimeframe()),
                dis,
                roAttr,
                "Таймфрейм стратегии, например 1m, 5m, 15m."
        )

               + fieldNumber(
                "cachedCandlesLimit",
                "Свечей в кэше",
                valInt(s.getCachedCandlesLimit()),
                "min='50' step='10'",
                dis,
                roAttr,
                "Количество свечей для истории и обучения."
        )

               + "  </div>"

               + (readOnly
                ? "<div class='alert alert-info small mt-3 mb-0'>"
                  + "Режим <b>AI</b>: параметры управляются автоматически."
                  + "</div>"
                : "<div class='alert alert-secondary small mt-3 mb-0'>"
                  + "Режим <b>MANUAL / HYBRID</b>: можно менять core-параметры нового скальпинга."
                  + "</div>"
               )

               + "</div>";
    }

    @Override
    public void handleSubmit(AdvancedRenderContext ctx) {
        if (ctx.getControlMode() == AdvancedControlMode.AI) {
            log.info("🔒 SCALPING advanced ignored (AI mode)");
            return;
        }

        Map<String, String> p = ctx.getParams();

        ScalpingStrategySettings incoming = ScalpingStrategySettings.builder()
                .chatId(ctx.getChatId())
                .windowSize(parseInt(p.get("windowSize")))
                .minImpulsePct(parseDouble(p.get("minImpulsePct")))
                .emaDiffThreshold(parseDouble(p.get("emaDiffThreshold")))
                .volumeRatio(parseDouble(p.get("volumeRatio")))
                .spreadLimitPct(parseDouble(p.get("spreadLimitPct")))
                .atrPctRange(parseDouble(p.get("atrPctRange")))
                .rsiFilter(parseDouble(p.get("rsiFilter")))
                .riskRewardMin(parseDouble(p.get("riskRewardMin")))
                .orderVolume(parseDouble(p.get("orderVolume")))
                .takeProfitPct(parseDouble(p.get("takeProfitPct")))
                .stopLossPct(parseDouble(p.get("stopLossPct")))
                .symbol(parseText(p.get("symbol")))
                .timeframe(parseText(p.get("timeframe")))
                .cachedCandlesLimit(parseInt(p.get("cachedCandlesLimit")))
                .build();

        scalpingSettingsService.update(ctx.getChatId(), incoming);

        log.info("✅ SCALPING V4 advanced settings saved (chatId={})", ctx.getChatId());
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

    private static String fieldText(
            String name,
            String label,
            String value,
            String dis,
            String roAttr,
            String help
    ) {
        String safe = value == null ? "" : HtmlUtils.htmlEscape(value);

        return "<div class='col-md-3'>"
               + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
               + "  <input type='text' class='form-control' name='" + HtmlUtils.htmlEscape(name) + "'"
               + "         value='" + safe + "'" + dis + roAttr + ">"
               + "  <div class='form-text'>" + HtmlUtils.htmlEscape(help) + "</div>"
               + "</div>";
    }

    private static String valInt(Integer v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String valDouble(Double v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String valText(String v) {
        return v == null ? "" : v;
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
            return v == null || v.isBlank() ? null : Double.parseDouble(v.trim().replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    private static String parseText(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        return v.trim();
    }
}
