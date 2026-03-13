package com.chicu.aitradebot.ai.ml.sidecar;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE) // ✅ сначала пытаемся восстановить базовую модель
public class MlModelBootstrapper implements ApplicationRunner {

    private static final String MODEL_FILE_NAME = "model.joblib";
    private static final String META_SUFFIX = ".meta.json";

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

            if (Files.exists(target) && isUsableModelFile(target)) {
                log.info("🧠 [ML-BOOT] model exists: {}", target);
                return;
            }

            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Path found = findBestCandidateModel(target);
            if (found == null) {
                log.warn("🧠 [ML-BOOT] model.joblib не найден (target останется пуст): {}", target);
                return;
            }

            if (sameFileSafe(found, target)) {
                log.info("🧠 [ML-BOOT] source == target, skip copy: {}", target);
                return;
            }

            Files.copy(found, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("🧠 [ML-BOOT] copied model: {} -> {}", found, target);

            copyMetaIfExists(found, target);

        } catch (Exception e) {
            log.warn("🧠 [ML-BOOT] failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Ищем ЛУЧШУЮ модель:
     * 1) сначала прямой model.joblib в известных местах
     * 2) потом самый свежий *.joblib во всех известных каталогах
     */
    private Path findBestCandidateModel(Path target) {
        List<Path> roots = collectSearchRoots(target);

        // 1. Сначала пробуем точное имя model.joblib
        for (Path root : roots) {
            if (root == null) continue;

            Path direct = root.resolve(MODEL_FILE_NAME).toAbsolutePath().normalize();
            if (sameFileSafe(direct, target)) continue;

            if (isUsableModelFile(direct)) {
                log.info("🧠 [ML-BOOT] found direct candidate: {}", direct);
                return direct;
            }
        }

        // 2. Затем ищем любой самый свежий *.joblib
        List<Path> allCandidates = new ArrayList<>();

        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) continue;

            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, "*.joblib")) {
                for (Path p : ds) {
                    Path abs = p.toAbsolutePath().normalize();

                    if (sameFileSafe(abs, target)) continue;
                    if (!isUsableModelFile(abs)) continue;

                    allCandidates.add(abs);
                }
            } catch (Exception e) {
                log.debug("🧠 [ML-BOOT] scan skipped root={} err={}", root, e.toString());
            }
        }

        if (allCandidates.isEmpty()) {
            return null;
        }

        allCandidates.sort(Comparator
                .comparing(this::lastModifiedSafe, Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed()
                .thenComparing(Path::toString));

        Path best = allCandidates.get(0);
        log.info("🧠 [ML-BOOT] found latest candidate: {}", best);
        return best;
    }

    private List<Path> collectSearchRoots(Path target) {
        List<Path> roots = new ArrayList<>();

        String userDir = safe(System.getProperty("user.dir"));
        String workDir = safe(sidecar.getWorkDir());

        addIfDirectoryOrPotentialRoot(roots, userDir);
        addIfDirectoryOrPotentialRoot(roots, workDir);

        if (userDir != null) {
            roots.add(Paths.get(userDir, "ml-models").toAbsolutePath().normalize());
        }
        if (workDir != null) {
            roots.add(Paths.get(workDir, "ml-models").toAbsolutePath().normalize());
        }

        Path targetParent = target != null ? target.getParent() : null;
        if (targetParent != null) {
            roots.add(targetParent.toAbsolutePath().normalize());
        }

        // убираем дубли
        List<Path> unique = new ArrayList<>();
        for (Path p : roots) {
            if (p == null) continue;

            boolean exists = false;
            for (Path u : unique) {
                if (samePath(u, p)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                unique.add(p);
            }
        }

        return unique;
    }

    private void addIfDirectoryOrPotentialRoot(List<Path> roots, String raw) {
        if (raw == null) return;
        try {
            Path p = Paths.get(raw).toAbsolutePath().normalize();
            roots.add(p);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private void copyMetaIfExists(Path sourceModel, Path targetModel) {
        try {
            Path sourceMeta = metaPath(sourceModel);
            Path targetMeta = metaPath(targetModel);

            if (Files.exists(sourceMeta) && Files.isRegularFile(sourceMeta)) {
                Files.copy(sourceMeta, targetMeta, StandardCopyOption.REPLACE_EXISTING);
                log.info("🧠 [ML-BOOT] copied meta: {} -> {}", sourceMeta, targetMeta);
            } else {
                log.info("🧠 [ML-BOOT] meta not found for source model: {}", sourceMeta);
            }
        } catch (Exception e) {
            log.warn("🧠 [ML-BOOT] meta copy failed: {}", e.getMessage());
        }
    }

    private Path metaPath(Path modelPath) {
        return Paths.get(modelPath.toString() + META_SUFFIX).toAbsolutePath().normalize();
    }

    private boolean isUsableModelFile(Path path) {
        try {
            if (path == null) return false;
            if (!Files.exists(path)) return false;
            if (!Files.isRegularFile(path)) return false;

            String fileName = path.getFileName() != null ? path.getFileName().toString() : "";
            if (!fileName.toLowerCase(Locale.ROOT).endsWith(".joblib")) return false;

            long size = Files.size(path);
            return size > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private FileTime lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean sameFileSafe(Path a, Path b) {
        if (a == null || b == null) return false;
        try {
            return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (Exception ignored) {
            return samePath(a, b);
        }
    }

    private boolean samePath(Path a, Path b) {
        if (a == null || b == null) return false;
        return a.toAbsolutePath().normalize().toString()
                .equalsIgnoreCase(b.toAbsolutePath().normalize().toString());
    }

    private static String safe(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}