package com.chicu.aitradebot.ai.ml.sidecar;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.File;

@Data
@Component
@ConfigurationProperties(prefix = "ml.sidecar")
public class MlSidecarProperties {

    /** Включить автозапуск python-sidecar */
    private boolean enabled = false;

    /** Путь к python (лучше к .venv) */
    private String python = "python";

    /** Uvicorn module, например: app.app:app */
    private String module = "app.app:app";

    /** host для uvicorn */
    private String host = "127.0.0.1";

    /** port для uvicorn */
    private int port = 8002;

    /** Рабочая директория (где лежит папка app/) */
    private String workDir = ".";

    /** Директория моделей (можешь использовать в Runner для MODEL_PATH и т.п.) */
    private String modelsDir = "./ml-models";

    /** Сколько ждать /health после старта процесса */
    private long startTimeoutMs = 15_000;

    /** Как часто опрашивать /health */
    private long pollEveryMs = 250;

    /** Опционально: явный MODEL_PATH. Если null — можно собрать из modelsDir */
    private String modelPath;

    public String resolveModelPathOrDefault() {
        if (modelPath != null && !modelPath.isBlank()) return modelPath.trim();
        return new File(modelsDir, "model.joblib").getPath();
    }
}