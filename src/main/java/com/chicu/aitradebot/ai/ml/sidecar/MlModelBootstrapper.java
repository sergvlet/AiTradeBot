package com.chicu.aitradebot.ai.ml.sidecar;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE) // ✅ 1) сначала кладём модель
public class MlModelBootstrapper implements ApplicationRunner {

    private final MlSidecarProperties sidecar;

    @Override
    public void run(ApplicationArguments args) {
        try {
            String targetStr = safe(sidecar.resolveModelPathOrDefault());
            if (targetStr == null) {
                log.warn("🧠 [ML-BOOT] target path is blank (resolveModelPathOrDefault)");
                return;
            }

            Path target = Paths.get(targetStr).toAbsolutePath().normalize();

            if (Files.exists(target)) {
                log.info("🧠 [ML-BOOT] model exists: {}", target);
                return;
            }

            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Path found = findCandidateModel();
            if (found == null) {
                log.warn("🧠 [ML-BOOT] model.joblib не найден (target останется пуст): {}", target);
                return;
            }

            // если вдруг target и found указывают на одно и то же
            if (found.equals(target)) {
                log.info("🧠 [ML-BOOT] source == target, skip copy: {}", target);
                return;
            }

            Files.copy(found, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("🧠 [ML-BOOT] copied model: {} -> {}", found, target);

        } catch (Exception e) {
            log.warn("🧠 [ML-BOOT] failed: {}", e.getMessage(), e);
        }
    }

    private Path findCandidateModel() {
        String userDir = safe(System.getProperty("user.dir"));
        String workDir = safe(sidecar.getWorkDir());

        Path[] candidates = new Path[] {
                // где чаще всего лежит “старая” модель
                (userDir != null ? Paths.get(userDir, "model.joblib") : null),
                (workDir != null ? Paths.get(workDir, "model.joblib") : null),
                (userDir != null ? Paths.get(userDir, "ml-models", "model.joblib") : null),
                (workDir != null ? Paths.get(workDir, "ml-models", "model.joblib") : null),
        };

        for (Path c : candidates) {
            try {
                if (c != null && Files.exists(c)) {
                    return c.toAbsolutePath().normalize();
                }
            } catch (Exception ignore) {
                // ignore
            }
        }
        return null;
    }

    private static String safe(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}