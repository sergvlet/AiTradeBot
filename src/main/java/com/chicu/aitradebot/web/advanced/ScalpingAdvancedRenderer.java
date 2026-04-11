package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.StrategySettings;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.service.StrategySettingsService;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettings;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScalpingAdvancedRenderer implements StrategyAdvancedRenderer {

    private final ScalpingStrategySettingsService scalpingSettingsService;
    private final StrategySettingsService strategySettingsService;

    @Override
    public StrategyType supports() {
        return StrategyType.SCALPING;
    }

    @Override
    public String render(AdvancedRenderContext ctx) {
        ScalpingStrategySettings s = scalpingSettingsService.getOrCreate(ctx.getChatId());
        StrategySettings strategy = strategySettingsService.getOrCreate(ctx.getChatId(), StrategyType.SCALPING);

        boolean readOnly = ctx.isReadOnly();
        String dis = readOnly ? " disabled" : "";
        String roAttr = readOnly ? " readonly" : "";

        StringBuilder html = new StringBuilder();
        html.append("<div class='card card-theme p-3 mb-3'>")
                .append("<div class='d-flex align-items-center justify-content-between mb-2'>")
                .append("<div class='fw-bold'>SCALPING V4 — параметры стратегии</div>")
                .append(readOnly ? "<span class='badge bg-info'>AI</span>" : "<span class='badge bg-secondary'>MANUAL / HYBRID</span>")
                .append("</div>")
                .append(mlCard(strategy))
                .append("<div class='row g-3'>")

                .append(sectionTitle("Торговый контекст"))
                .append(infoField("Символ", valText(s.getSymbol()), "Источник истины — вкладка «Торговля»."))
                .append(infoField("Таймфрейм", valText(s.getTimeframe()), "Источник истины — вкладка «Торговля»."))
                .append(infoField("Кэш свечей", valInt(s.getCachedCandlesLimit()), "Источник истины — вкладка «Торговля»."))
                .append(infoField("Активна", Boolean.TRUE.equals(s.getActive()) ? "Да" : "Нет", "Флаг активности синхронизируется из StrategySettings / runtime."))

                .append(sectionTitle("Режим рынка"))
                .append(fieldCheckbox("regimeAutoEnabled", "Авто-режим рынка", s.getRegimeAutoEnabled(), dis, "Бот сам определяет режим рынка."))
                .append(fieldCheckbox("allowTrendTrades", "Trend", s.getAllowTrendTrades(), dis, "Разрешить трендовые сделки."))
                .append(fieldCheckbox("allowRangeTrades", "Range", s.getAllowRangeTrades(), dis, "Разрешить боковик."))
                .append(fieldCheckbox("allowBreakoutTrades", "Breakout", s.getAllowBreakoutTrades(), dis, "Разрешить continuation после squeeze."))
                .append(fieldCheckbox("allowCounterTrendTrades", "Counter-trend", s.getAllowCounterTrendTrades(), dis, "Для spot обычно выключено."))
                .append(fieldNumber("chaosBlockThreshold", "Chaos block", valDouble(s.getChaosBlockThreshold()), "min='0' max='100' step='0.1'", dis, roAttr, "Порог блокировки по хаосу."))
                .append(fieldNumber("squeezeThreshold", "Squeeze threshold", valDouble(s.getSqueezeThreshold()), "min='0' max='100' step='0.1'", dis, roAttr, "Порог squeeze."))

                .append(sectionTitle("Trend Pullback"))
                .append(fieldNumber("trendMinScore", "Trend min score", valDouble(s.getTrendMinScore()), "min='0' max='100' step='0.1'", dis, roAttr, "Минимальный score для тренда."))
                .append(fieldNumber("pullbackMaxDepthPct", "Max depth %", valDouble(s.getPullbackMaxDepthPct()), "min='0.05' max='10' step='0.01'", dis, roAttr, "Максимальная глубина отката."))
                .append(fieldNumber("pullbackEntryBufferPct", "Entry buffer %", valDouble(s.getPullbackEntryBufferPct()), "min='0.01' max='5' step='0.01'", dis, roAttr, "Буфер входа после отката."))
                .append(fieldNumber("trendTpPct", "Trend TP %", valDouble(s.getTrendTpPct()), "min='0.01' step='0.01'", dis, roAttr, "TP для тренда."))
                .append(fieldNumber("trendSlPct", "Trend SL %", valDouble(s.getTrendSlPct()), "min='0.01' step='0.01'", dis, roAttr, "SL для тренда."))
                .append(fieldNumber("trendBreakEvenPct", "BE trigger %", valDouble(s.getTrendBreakEvenPct()), "min='0.01' step='0.01'", dis, roAttr, "Перевод в безубыток."))
                .append(fieldNumber("trendMaxHoldSec", "Trend max hold sec", valInt(s.getTrendMaxHoldSec()), "min='5' step='1'", dis, roAttr, "Time stop в тренде."))

                .append(sectionTitle("Range Bounce"))
                .append(fieldNumber("rangeMinScore", "Range min score", valDouble(s.getRangeMinScore()), "min='0' max='100' step='0.1'", dis, roAttr, "Минимальный score для боковика."))
                .append(fieldNumber("rangeEntryFromLowPct", "Entry from low %", valDouble(s.getRangeEntryFromLowPct()), "min='0.01' step='0.01'", dis, roAttr, "Точка входа у нижней границы."))
                .append(fieldNumber("rangeExitToMidPct", "Exit to mid %", valDouble(s.getRangeExitToMidPct()), "min='0.01' step='0.01'", dis, roAttr, "Цель в боковике."))
                .append(fieldNumber("rangeTpPct", "Range TP %", valDouble(s.getRangeTpPct()), "min='0.01' step='0.01'", dis, roAttr, "TP для range."))
                .append(fieldNumber("rangeSlPct", "Range SL %", valDouble(s.getRangeSlPct()), "min='0.01' step='0.01'", dis, roAttr, "SL для range."))
                .append(fieldNumber("rangeMaxHoldSec", "Range max hold sec", valInt(s.getRangeMaxHoldSec()), "min='5' step='1'", dis, roAttr, "Time stop в range."))

                .append(sectionTitle("Breakout"))
                .append(fieldNumber("breakoutMinScore", "Breakout min score", valDouble(s.getBreakoutMinScore()), "min='0' max='100' step='0.1'", dis, roAttr, "Минимальный score для breakout."))
                .append(fieldNumber("breakoutVolumeFactor", "Volume factor", valDouble(s.getBreakoutVolumeFactor()), "min='0.1' step='0.01'", dis, roAttr, "Фактор объёма для breakout."))
                .append(fieldNumber("breakoutTpPct", "Breakout TP %", valDouble(s.getBreakoutTpPct()), "min='0.01' step='0.01'", dis, roAttr, "TP для breakout."))
                .append(fieldNumber("breakoutSlPct", "Breakout SL %", valDouble(s.getBreakoutSlPct()), "min='0.01' step='0.01'", dis, roAttr, "SL для breakout."))

                .append(sectionTitle("Execution / Risk"))
                .append(fieldNumber("maxSpreadPct", "Max spread %", valDouble(s.getMaxSpreadPct()), "min='0' step='0.01'", dis, roAttr, "Максимальный допустимый spread."))
                .append(fieldNumber("minAtrPct", "Min ATR %", valDouble(s.getMinAtrPct()), "min='0' step='0.01'", dis, roAttr, "Нижний порог ATR."))
                .append(fieldNumber("maxAtrPct", "Max ATR %", valDouble(s.getMaxAtrPct()), "min='0' step='0.01'", dis, roAttr, "Верхний порог ATR."))
                .append(fieldNumber("minVolumeRatio", "Min volume ratio", valDouble(s.getMinVolumeRatio()), "min='0' step='0.01'", dis, roAttr, "Минимальный коэффициент объёма."))
                .append(fieldNumber("minRiskReward", "Min RR", valDouble(s.getMinRiskReward()), "min='0.1' step='0.01'", dis, roAttr, "Минимальный risk/reward."))
                .append(fieldNumber("cooldownAfterStopSec", "Cooldown after stop", valInt(s.getCooldownAfterStopSec()), "min='0' step='1'", dis, roAttr, "Пауза после стопа."))
                .append(fieldNumber("cooldownAfterExitSec", "Cooldown after exit", valInt(s.getCooldownAfterExitSec()), "min='0' step='1'", dis, roAttr, "Пауза после выхода."))
                .append(fieldNumber("maxConsecutiveStops", "Max stops", valInt(s.getMaxConsecutiveStops()), "min='1' step='1'", dis, roAttr, "Лимит стопов подряд."))
                .append(fieldNumber("reentryLockSec", "Re-entry lock sec", valInt(s.getReentryLockSec()), "min='0' step='1'", dis, roAttr, "Запрет мгновенного перевхода."))
                .append(fieldCheckbox("partialExitEnabled", "Partial exit", s.getPartialExitEnabled(), dis, "Разрешить частичный выход."))
                .append(fieldNumber("partialExitPct", "Partial exit pct", valDouble(s.getPartialExitPct()), "min='0.05' max='0.95' step='0.01'", dis, roAttr, "Размер partial exit."))
                .append(fieldNumber("partialExitTriggerPct", "Partial trigger %", valDouble(s.getPartialExitTriggerPct()), "min='0.01' step='0.01'", dis, roAttr, "Триггер partial exit."))
                .append(fieldCheckbox("emergencyChaosExitEnabled", "Emergency chaos exit", s.getEmergencyChaosExitEnabled(), dis, "Аварийный выход в хаосе."))
                .append(fieldCheckbox("useIntrabarConfirmation", "Intrabar confirm", s.getUseIntrabarConfirmation(), dis, "Micro-confirmation внутри свечи."))
                .append(fieldNumber("microWindowSize", "Micro window", valInt(s.getMicroWindowSize()), "min='3' step='1'", dis, roAttr, "Окно intrabar-логики."))

                .append(sectionTitle("Базовые параметры"))
                .append(fieldNumber("windowSize", "Окно", valInt(s.getWindowSize()), "min='5' step='1'", dis, roAttr, "Рабочее окно."))
                .append(fieldNumber("minImpulsePct", "Мин. импульс %", valDouble(s.getMinImpulsePct()), "min='0' step='0.01'", dis, roAttr, "Legacy-порог для fallback/ML."))
                .append(fieldNumber("emaDiffThreshold", "EMA diff %", valDouble(s.getEmaDiffThreshold()), "min='0' step='0.01'", dis, roAttr, "Legacy EMA diff threshold."))
                .append(fieldNumber("volumeRatio", "Legacy volume ratio", valDouble(s.getVolumeRatio()), "min='0' step='0.01'", dis, roAttr, "Legacy volume ratio."))
                .append(fieldNumber("spreadLimitPct", "Legacy spread %", valDouble(s.getSpreadLimitPct()), "min='0' step='0.01'", dis, roAttr, "Legacy spread limit."))
                .append(fieldNumber("atrPctRange", "Legacy ATR range %", valDouble(s.getAtrPctRange()), "min='0' step='0.01'", dis, roAttr, "Legacy ATR range."))
                .append(fieldNumber("rsiFilter", "Legacy RSI", valDouble(s.getRsiFilter()), "min='1' max='99' step='0.1'", dis, roAttr, "Legacy RSI filter."))
                .append(fieldNumber("riskRewardMin", "Legacy RR", valDouble(s.getRiskRewardMin()), "min='0.1' step='0.01'", dis, roAttr, "Legacy risk/reward filter."))
                .append(fieldNumber("orderVolume", "Order volume", valDouble(s.getOrderVolume()), "min='1' step='0.1'", dis, roAttr, "Объём ордера в quote."))
                .append(fieldNumber("takeProfitPct", "Legacy TP %", valDouble(s.getTakeProfitPct()), "min='0.01' step='0.01'", dis, roAttr, "Legacy take profit."))
                .append(fieldNumber("stopLossPct", "Legacy SL %", valDouble(s.getStopLossPct()), "min='0.01' step='0.01'", dis, roAttr, "Legacy stop loss."))
                .append("</div>")
                .append(readOnly
                        ? "<div class='alert alert-info small mt-3 mb-0'>Режим <b>AI</b>: параметры и ML-гейт управляются автоматически.</div>"
                        : "<div class='alert alert-secondary small mt-3 mb-0'>Режим <b>MANUAL / HYBRID</b>: можно менять только стратегические параметры. Символ / таймфрейм / кэш свечей меняются во вкладке <b>Торговля</b>.</div>")
                .append("</div>");

        return html.toString();
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
                .regimeAutoEnabled(parseBool(p.get("regimeAutoEnabled")))
                .allowTrendTrades(parseBool(p.get("allowTrendTrades")))
                .allowRangeTrades(parseBool(p.get("allowRangeTrades")))
                .allowBreakoutTrades(parseBool(p.get("allowBreakoutTrades")))
                .allowCounterTrendTrades(parseBool(p.get("allowCounterTrendTrades")))
                .chaosBlockThreshold(parseDouble(p.get("chaosBlockThreshold")))
                .squeezeThreshold(parseDouble(p.get("squeezeThreshold")))
                .trendMinScore(parseDouble(p.get("trendMinScore")))
                .pullbackMaxDepthPct(parseDouble(p.get("pullbackMaxDepthPct")))
                .pullbackEntryBufferPct(parseDouble(p.get("pullbackEntryBufferPct")))
                .trendTpPct(parseDouble(p.get("trendTpPct")))
                .trendSlPct(parseDouble(p.get("trendSlPct")))
                .trendBreakEvenPct(parseDouble(p.get("trendBreakEvenPct")))
                .trendMaxHoldSec(parseInt(p.get("trendMaxHoldSec")))
                .rangeMinScore(parseDouble(p.get("rangeMinScore")))
                .rangeEntryFromLowPct(parseDouble(p.get("rangeEntryFromLowPct")))
                .rangeExitToMidPct(parseDouble(p.get("rangeExitToMidPct")))
                .rangeTpPct(parseDouble(p.get("rangeTpPct")))
                .rangeSlPct(parseDouble(p.get("rangeSlPct")))
                .rangeMaxHoldSec(parseInt(p.get("rangeMaxHoldSec")))
                .breakoutMinScore(parseDouble(p.get("breakoutMinScore")))
                .breakoutVolumeFactor(parseDouble(p.get("breakoutVolumeFactor")))
                .breakoutTpPct(parseDouble(p.get("breakoutTpPct")))
                .breakoutSlPct(parseDouble(p.get("breakoutSlPct")))
                .maxSpreadPct(parseDouble(p.get("maxSpreadPct")))
                .minAtrPct(parseDouble(p.get("minAtrPct")))
                .maxAtrPct(parseDouble(p.get("maxAtrPct")))
                .minVolumeRatio(parseDouble(p.get("minVolumeRatio")))
                .minRiskReward(parseDouble(p.get("minRiskReward")))
                .cooldownAfterStopSec(parseInt(p.get("cooldownAfterStopSec")))
                .cooldownAfterExitSec(parseInt(p.get("cooldownAfterExitSec")))
                .maxConsecutiveStops(parseInt(p.get("maxConsecutiveStops")))
                .reentryLockSec(parseInt(p.get("reentryLockSec")))
                .emergencyChaosExitEnabled(parseBool(p.get("emergencyChaosExitEnabled")))
                .partialExitEnabled(parseBool(p.get("partialExitEnabled")))
                .partialExitPct(parseDouble(p.get("partialExitPct")))
                .partialExitTriggerPct(parseDouble(p.get("partialExitTriggerPct")))
                .useIntrabarConfirmation(parseBool(p.get("useIntrabarConfirmation")))
                .microWindowSize(parseInt(p.get("microWindowSize")))
                .build();

        scalpingSettingsService.update(ctx.getChatId(), incoming);
        log.info("✅ SCALPING V4 advanced settings saved (chatId={})", ctx.getChatId());
    }

    private static String mlCard(StrategySettings strategy) {
        String modelKey = esc(strategy != null ? strategy.getMlModelKey() : null);
        String modelVersion = esc(strategy != null ? strategy.getMlModelVersion() : null);
        String schemaHash = esc(strategy != null ? strategy.getMlSchemaHash() : null);
        String gate = strategy != null && strategy.getGateMinProb() != null
                ? esc(strategy.getGateMinProb().stripTrailingZeros().toPlainString())
                : "—";
        String mode = strategy != null && strategy.getAdvancedControlMode() != null
                ? esc(strategy.getAdvancedControlMode().name())
                : "MANUAL";
        String phase = strategy != null && strategy.getRunPhase() != null
                ? esc(strategy.getRunPhase())
                : "LIVE";
        String mlGate = strategy != null ? (strategy.isMlGateEnabled() ? "ON" : "OFF") : "OFF";
        String autoTune = strategy != null ? (strategy.isAutoTuneEnabled() ? "ON" : "OFF") : "OFF";

        return "<div class='alert alert-dark border mb-3'><div class='fw-semibold mb-2'>ML / AI статус</div><div class='row g-2 small'>"
                + valueCol("Mode", mode)
                + valueCol("Phase", phase)
                + valueCol("ML gate", mlGate)
                + valueCol("Auto-tune", autoTune)
                + valueCol("Gate threshold", gate)
                + valueCol("Model version", modelVersion)
                + "<div class='col-md-12'><div><b>Model key:</b> " + modelKey + "</div><div><b>Schema hash:</b> " + schemaHash + "</div></div></div></div>";
    }

    private static String sectionTitle(String title) {
        return "<div class='col-12 pt-2'><div class='fw-semibold text-uppercase small text-secondary border-top pt-3'>"
                + esc(title) + "</div></div>";
    }

    private static String valueCol(String label, String value) {
        return "<div class='col-md-3'><div><b>" + esc(label) + ":</b> " + esc(value) + "</div></div>";
    }

    private static String infoField(String label, String value, String help) {
        String safe = value == null ? "" : HtmlUtils.htmlEscape(value);
        return "<div class='col-md-3'><label class='form-label'>" + esc(label) + "</label>"
                + "<input type='text' class='form-control' value='" + safe + "' readonly disabled>"
                + "<div class='form-text'>" + esc(help) + "</div></div>";
    }

    private static String fieldNumber(String name, String label, String value, String extraAttrs, String dis, String roAttr, String help) {
        String safe = value == null ? "" : HtmlUtils.htmlEscape(value);
        return "<div class='col-md-3'><label class='form-label'>" + esc(label) + "</label><input type='number' class='form-control' name='"
                + esc(name) + "' value='" + safe + "' " + extraAttrs + dis + roAttr + "><div class='form-text'>"
                + esc(help) + "</div></div>";
    }

    private static String fieldCheckbox(String name, String label, Boolean value, String dis, String help) {
        boolean checked = Boolean.TRUE.equals(value);
        return "<div class='col-md-3'><label class='form-label d-block'>" + esc(label) + "</label><div class='form-check form-switch mt-2'>"
                + "<input class='form-check-input' type='checkbox' name='" + esc(name) + "' value='true'"
                + (checked ? " checked" : "") + dis + "><label class='form-check-label'>"
                + (checked ? "Вкл" : "Выкл") + "</label></div><div class='form-text'>"
                + esc(help) + "</div></div>";
    }

    private static String valInt(Integer value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String valDouble(Double value) {
        return value == null ? "" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String valText(String value) {
        return value == null ? "" : value;
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null || value.isBlank() ? "—" : value);
    }

    private static Integer parseInt(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        try {
            return value == null || value.isBlank() ? null : Double.parseDouble(value.trim().replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean parseBool(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase();
        return v.equals("true") || v.equals("on") || v.equals("1") || v.equals("yes");
    }
}
