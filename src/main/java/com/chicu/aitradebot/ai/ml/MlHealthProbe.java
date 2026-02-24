package com.chicu.aitradebot.ai.ml;

import com.chicu.aitradebot.ai.ml.dto.MlHealthResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class MlHealthProbe implements ApplicationRunner {

    private final MlProperties props;
    private final MlClient client;

    /**
     * Прод-режим: если ML включён и недоступен — валим старт приложения.
     * По умолчанию false (удобно для dev).
     */
    @Value("${ml.failFast:false}")
    private boolean failFast;

    /**
     * Прод-режим: если ML включён, но модели нет — считаем это проблемой.
     * По умолчанию false.
     */
    @Value("${ml.requireModel:false}")
    private boolean requireModel;

    @Override
    public void run(ApplicationArguments args) {
        if (props == null || !props.isEnabled()) {
            log.info("🧠 ML выключен (ml.enabled=false). Health-check пропущен.");
            return;
        }

        String baseUrl = (props.getBaseUrl() == null ? "" : props.getBaseUrl().trim());
        if (baseUrl.isEmpty()) {
            String msg = "🧠 ML включён (ml.enabled=true), но ml.baseUrl пустой";
            if (failFast) throw new IllegalStateException(msg);
            log.warn(msg);
            return;
        }

        String healthUrl = trimSlash(baseUrl) + "/health";
        log.info("🧠 ML включён (ml.enabled=true). Проверяю /health: {}", healthUrl);

        try {
            MlHealthResponse h = client.health();

            // ✅ НИКАКИХ прямых getOk()/getModelExists() — всё через reflection, чтобы DTO не ломал компиляцию
            Boolean ok = asBool(readAny(h, "getOk", "isOk", "ok"));
            if (ok == null || !ok) {
                String err = asStr(readAny(h,
                        "getError", "error",
                        "getMessage", "message"
                ));
                String msg = "❌ ML service NOT OK: ok=" + ok + " error=" + (err == null ? "null" : err);
                if (failFast) throw new IllegalStateException(msg);
                log.warn("🧠 {}", msg);
                return;
            }

            Boolean xgboost = asBool(readAny(h, "isXgboost", "getXgboost", "getXGBoost", "xgboost"));
            Boolean modelExists = asBool(readAny(h,
                    "isModelExists", "getModelExists", "modelExists",
                    "getModel_exists", "model_exists"
            ));

            String modelVersion = asStr(readAny(h,
                    "getModelVersion", "modelVersion",
                    "getModel_version", "model_version"
            ));

            String version = asStr(readAny(h, "getVersion", "version"));
            String modelsDir = asStr(readAny(h, "getModelsDir", "modelsDir"));

            log.info("✅ ML service OK: ok=true version={} xgboost={} model_exists={} modelVersion={} modelsDir={}",
                    nn(version), nn(xgboost), nn(modelExists), nn(modelVersion), nn(modelsDir));

            if (requireModel) {
                if (modelExists == null) {
                    // DTO/ответ не отдал model_exists — не можем строго проверить, но предупреждаем
                    log.warn("⚠️ ml.requireModel=true, но в health-ответе нет model_exists. Добавь поле/геттер в MlHealthResponse, если хочешь строгую проверку.");
                } else if (!modelExists) {
                    String msg = "❌ ml.requireModel=true, но model_exists=false — сначала обучи модель (/train), потом запускай AI/HYBRID";
                    if (failFast) throw new IllegalStateException(msg);
                    log.warn(msg);
                }
            }

        } catch (Exception e) {
            String msg = "❌ ML health-check failed: " + e.getMessage();
            if (failFast) throw new IllegalStateException(msg, e);
            log.warn("🧠 {}", msg);
        }
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    /**
     * Пытаемся прочитать:
     * 1) public method (0 args) по имени
     * 2) getter/is-variant если передали "ok"/"error"/...
     * 3) поле по имени (включая private)
     */
    private static Object readAny(Object target, String... names) {
        if (target == null || names == null) return null;

        for (String n : names) {
            if (n == null || n.isBlank()) continue;

            // 1) public method exact
            try {
                for (Method m : target.getClass().getMethods()) {
                    if (!m.getName().equals(n)) continue;
                    if (m.getParameterCount() != 0) continue;
                    return m.invoke(target);
                }
            } catch (Exception ignore) { /* ignore */ }

            // 2) getter/is variants если n выглядит как "ok"
            if (!n.startsWith("get") && !n.startsWith("is")) {
                String cap = Character.toUpperCase(n.charAt(0)) + n.substring(1);

                try {
                    Method m = target.getClass().getMethod("get" + cap);
                    return m.invoke(target);
                } catch (Exception ignore) { /* ignore */ }

                try {
                    Method m = target.getClass().getMethod("is" + cap);
                    return m.invoke(target);
                } catch (Exception ignore) { /* ignore */ }
            }

            // 3) field exact (including private)
            Object fv = readField(target, n);
            if (fv != null) return fv;
        }

        return null;
    }

    private static Object readField(Object target, String fieldName) {
        try {
            Class<?> c = target.getClass();
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception ignore) {
            // ignore
        }
        return null;
    }

    private static Boolean asBool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("n")) return false;
        return null;
    }

    private static String asStr(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String nn(Object v) {
        return v == null ? "null" : String.valueOf(v);
    }
}