package com.chicu.aitradebot.ai.ml.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // ✅ после MlModelBootstrapper, но до MlHealthProbe
public class MlSidecarRunner implements ApplicationRunner {

    private final MlSidecarProperties props;
    private final ObjectMapper om;

    private final Object lock = new Object();
    private volatile Process process;

    @Override
    public void run(ApplicationArguments args) {
        synchronized (lock) {

            String host = propStr("host", "getHost", "127.0.0.1");
            int port = propInt("port", "getPort", 8002);
            String baseUrl = "http://" + host + ":" + port;

            // 0) если уже поднят (кем-то или предыдущим запуском) — не трогаем
            if (isHealthy(baseUrl)) {
                log.info("🧠 [ML-SIDECAR] уже запущен: {}", baseUrl);
                return;
            }

            // 1) собираем команду
            String python = propStr("python", "getPython", null);
            String module = propStr("module", "getModule", "app.app:app");
            String workDir = propStr("workDir", "getWorkDir", System.getProperty("user.dir"));
            String modelsDir = propStr("modelsDir", "getModelsDir", new File(workDir, "ml-models").getAbsolutePath());

            long startTimeoutMs = propLong("startTimeoutMs", "getStartTimeoutMs", 25_000L);
            long pollEveryMs = propLong("pollEveryMs", "getPollEveryMs", 250L);

            if (python == null || python.isBlank()) {
                log.error("🧠 [ML-SIDECAR] python path is empty (props.python)");
                return;
            }

            // нормализуем папки
            File wd = new File(workDir);
            if (!wd.exists()) {
                log.warn("🧠 [ML-SIDECAR] workDir does not exist, fallback to user.dir: {}", workDir);
                wd = new File(System.getProperty("user.dir"));
            }

            File md = new File(modelsDir);
            if (!md.exists() && !md.mkdirs()) {
                log.warn("🧠 [ML-SIDECAR] cannot create modelsDir: {}", modelsDir);
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(python);
            cmd.add("-m");
            cmd.add("uvicorn");
            cmd.add(module);
            cmd.add("--host");
            cmd.add(host);
            cmd.add("--port");
            cmd.add(String.valueOf(port));

            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(wd);
                pb.redirectErrorStream(true);

                // env для моделей
                Map<String, String> env = pb.environment();
                env.put("ML_MODELS_DIR", md.getAbsolutePath());

                // ✅ КРИТИЧНО: MODEL_PATH должен смотреть на model.joblib
                String modelPath = new File(md, "model.joblib").getAbsolutePath();
                env.put("MODEL_PATH", modelPath);

                log.info("🧠 [ML-SIDECAR] стартую: {}", String.join(" ", cmd));
                log.info("🧠 [ML-SIDECAR] workDir={}", pb.directory().getAbsolutePath());
                log.info("🧠 [ML-SIDECAR] ML_MODELS_DIR={}", md.getAbsolutePath());
                log.info("🧠 [ML-SIDECAR] MODEL_PATH={}", modelPath);

                process = pb.start();

                Thread t = new Thread(() -> pumpLogs(process), "ml-sidecar-logs");
                t.setDaemon(true);
                t.start();

                // 2) ждём /health
                long deadline = System.currentTimeMillis() + startTimeoutMs;
                while (System.currentTimeMillis() < deadline) {

                    Process p = process;
                    if (p == null) break;

                    if (!p.isAlive()) {
                        int code = p.exitValue();
                        log.error("🧠 [ML-SIDECAR] процесс умер при старте, exitCode={}", code);
                        return;
                    }

                    if (isHealthy(baseUrl)) {
                        log.info("🧠 [ML-SIDECAR] ✅ поднялся: {}", baseUrl);
                        return;
                    }

                    sleep(pollEveryMs);
                }

                log.error("🧠 [ML-SIDECAR] ❌ не дождался /health за {} ms: {}", startTimeoutMs, baseUrl);

            } catch (Exception e) {
                log.error("🧠 [ML-SIDECAR] ❌ ошибка запуска: {}", e.getMessage(), e);
            }
        }
    }

    private void pumpLogs(Process p) {
        if (p == null) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                log.info("🧠 [ML] {}", line);
            }
        } catch (Exception e) {
            log.debug("🧠 [ML-SIDECAR] log pump stopped: {}", e.getMessage());
        }
    }

    private boolean isHealthy(String baseUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(1200))
                    .build();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofMillis(1500))
                    .GET()
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return false;

            JsonNode j = om.readTree(resp.body());
            return j.path("ok").asBoolean(false);

        } catch (Exception ignore) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    @PreDestroy
    public void stop() {
        synchronized (lock) {
            Process p = process;
            process = null;
            if (p == null) return;

            try {
                log.info("🧠 [ML-SIDECAR] stopping...");
                p.destroy();
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    // =====================================================
    // props helpers (поддержка и record, и @Data)
    // =====================================================

    private String propStr(String recordAccessor, String getter, String def) {
        Object v = propAny(recordAccessor, getter);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private int propInt(String recordAccessor, String getter, int def) {
        Object v = propAny(recordAccessor, getter);
        if (v == null) return def;
        try { return Integer.parseInt(String.valueOf(v).trim()); }
        catch (Exception e) { return def; }
    }

    private long propLong(String recordAccessor, String getter, long def) {
        Object v = propAny(recordAccessor, getter);
        if (v == null) return def;
        try { return Long.parseLong(String.valueOf(v).trim()); }
        catch (Exception e) { return def; }
    }

    private Object propAny(String recordAccessor, String getter) {
        try {
            // record accessor: host()
            Method m = props.getClass().getMethod(recordAccessor);
            m.setAccessible(true);
            return m.invoke(props);
        } catch (Exception ignore) {
            // ignore
        }
        try {
            // классический getter: getHost()
            Method m = props.getClass().getMethod(getter);
            m.setAccessible(true);
            return m.invoke(props);
        } catch (Exception ignore) {
            return null;
        }
    }
}