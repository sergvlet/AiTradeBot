package com.chicu.aitradebot.web.advanced;

import com.chicu.aitradebot.common.enums.StrategyType;
import com.chicu.aitradebot.domain.enums.AdvancedControlMode;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettings;
import com.chicu.aitradebot.strategy.scalping.ScalpingStrategySettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;

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

        ScalpingStrategySettings s =
                scalpingSettingsService.getOrCreate(ctx.getChatId());

        boolean ro = ctx.isReadOnly();

        String dis    = ro ? " disabled" : "";
        String roAttr = ro ? " readonly" : "";

        return ""
               + "<div class='card card-theme p-3 mb-3'>"

               + "  <div class='d-flex align-items-center justify-content-between mb-2'>"
               + "    <div class='fw-bold'>SCALPING — параметры стратегии</div>"
               +      badge(ro)
               + "  </div>"

               + "  <div class='row g-3'>"

               + fieldNumber(
                "windowSize",
                "Окно анализа",
                valInt(s.getWindowSize()),
                "min='1' step='1'",
                dis,
                roAttr,
                "Сколько последних свечей используется для анализа."
        )

               + fieldNumber(
                "priceChangeThreshold",
                "Порог движения (%)",
                valDouble(s.getPriceChangeThreshold()),
                "min='0' step='0.01'",
                dis,
                roAttr,
                "Минимальное изменение цены, чтобы считать движение значимым."
        )

               + fieldNumber(
                "spreadThreshold",
                "Макс. спред (%)",
                valDouble(s.getSpreadThreshold()),
                "min='0' step='0.01'",
                dis,
                roAttr,
                "Если спред выше — вход в сделку запрещён."
        )

               + "  </div>"

               + (ro
                ? "<div class='alert alert-info small mt-3 mb-0'>"
                  + "Режим <b>AI</b>: параметры управляются автоматически и заблокированы."
                  + "</div>"
                : "<div class='alert alert-secondary small mt-3 mb-0'>"
                  + "Режим <b>MANUAL / HYBRID</b>: параметры можно менять вручную."
                  + "</div>"
               )

               + "</div>";
    }



    private static String badge(boolean ro) {
        if (ro) {
            return "<span class='badge bg-info'>AI</span>";
        }
        return "<span class='badge bg-secondary'>MANUAL/HYBRID</span>";
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
        String safeValue = value == null ? "" : HtmlUtils.htmlEscape(value);

        return ""
                + "<div class='col-md-3'>"
                + "  <label class='form-label'>" + HtmlUtils.htmlEscape(label) + "</label>"
                + "  <input type='number' class='form-control' name='" + HtmlUtils.htmlEscape(name) + "'"
                + "         value='" + safeValue + "' " + extraAttrs + dis + roAttr + ">"
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
        return v == null ? "" : v.toPlainString();
    }
}
