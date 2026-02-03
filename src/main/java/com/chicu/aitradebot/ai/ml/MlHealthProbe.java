package com.chicu.aitradebot.ai.ml;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class MlHealthProbe implements ApplicationRunner {

    private final MlClient mlClient;
    private final MlProperties props;

    @Override
    public void run(ApplicationArguments args) {

        // ✅ Если ML выключен — это нормальный режим. Не пишем “NOT available”.
        if (!props.isEnabled()) {
            log.info("🧠 ML отключён (ml.enabled=false) — работаем без ML");
            return;
        }

        try {
            JsonNode node = mlClient.health();

            // health() может вернуть MissingNode только если ml.enabled=false (но мы выше уже проверили)
            if (node == null || node.isMissingNode()) {
                log.warn("⚠️ ML включён, но /health вернул пустой ответ");
                return;
            }

            log.info("✅ ML service OK: {}", node);

        } catch (Exception e) {
            // Здесь управляй уровнем через конфиг (WARN/DEBUG), чтобы не бесило.
            String lvl = normalizeLevel(props.getHealthStartupLogLevel());
            String msg = "ML service NOT available: " + e.getMessage();

            if ("DEBUG".equals(lvl)) {
                log.debug("⚠️ {}", msg);
            } else {
                log.warn("⚠️ {}", msg);
            }
        }
    }

    private static String normalizeLevel(String s) {
        if (s == null) return "WARN";
        String x = s.trim().toUpperCase(Locale.ROOT);
        return x.equals("DEBUG") ? "DEBUG" : "WARN";
    }
}
