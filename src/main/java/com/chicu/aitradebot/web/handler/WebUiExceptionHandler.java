package com.chicu.aitradebot.web.handler;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@Slf4j
@ControllerAdvice(annotations = Controller.class)
public class WebUiExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public String handleIllegalState(IllegalStateException ex, HttpServletRequest req, Model model) {

        String msg = ex.getMessage() == null ? "" : ex.getMessage();

        // Ловим конкретно твой кейс: "StrategySettings not found chatId=... type=..."
        if (msg.startsWith("StrategySettings not found")) {

            log.warn("⚠ UI: стратегия не настроена: {} | path={}", msg, req.getRequestURI());

            // попробуем вытащить type/chatId из текста, чтобы отрисовать кнопку "Настройки"
            Long chatId = extractLong(msg, "chatId=");
            String type = extractString(msg, "type=");

            model.addAttribute("errorTitle", "Стратегия ещё не настроена");
            model.addAttribute("errorText", "Сначала открой настройки стратегии и сохрани параметры (пара/таймфрейм/биржа/сеть).");
            model.addAttribute("chatId", chatId);
            model.addAttribute("type", type);

            // пробрасываем query-параметры (exchange/network), если они были в URL
            model.addAttribute("exchange", req.getParameter("exchange"));
            model.addAttribute("network", req.getParameter("network"));

            return "views/strategies/not-configured";
        }

        // если это не тот кейс — пусть будет честная страница ошибки, но без 500 JSON
        log.error("❌ UI IllegalStateException: {} | path={}", msg, req.getRequestURI(), ex);

        model.addAttribute("errorTitle", "Ошибка");
        model.addAttribute("errorText", msg.isBlank() ? "Неизвестная ошибка" : msg);

        return "views/strategies/not-configured";
    }

    private static Long extractLong(String msg, String key) {
        try {
            int i = msg.indexOf(key);
            if (i < 0) return null;
            i += key.length();
            int j = i;
            while (j < msg.length() && Character.isDigit(msg.charAt(j))) j++;
            return Long.valueOf(msg.substring(i, j));
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String extractString(String msg, String key) {
        try {
            int i = msg.indexOf(key);
            if (i < 0) return null;
            i += key.length();
            int j = i;
            while (j < msg.length() && msg.charAt(j) != ' ' && msg.charAt(j) != ',' && msg.charAt(j) != ')') j++;
            return msg.substring(i, j);
        } catch (Exception ignore) {
            return null;
        }
    }
}
