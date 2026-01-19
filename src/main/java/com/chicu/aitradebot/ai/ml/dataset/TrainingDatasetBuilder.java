package com.chicu.aitradebot.ai.ml.dataset;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TrainingDatasetBuilder
 * ======================
 * Собирает датасет для обучения:
 * - X: double[][]
 * - y: int[]
 *
 * Важно: в коде MlTrainingService используются вложенные типы
 * TrainingDatasetBuilder.Rows и TrainingDatasetBuilder.Dataset
 * — поэтому они объявлены именно здесь.
 */
@Slf4j
@Service
public class TrainingDatasetBuilder {

    /**
     * Сырые строки датасета до сборки.
     * Xrows: список фич-векторов (каждый double[])
     * y: список меток (0/1)
     */
    public record Rows(
            String datasetId,
            List<double[]> Xrows,
            List<Integer> y
    ) {
        public static Rows empty() {
            return new Rows(UUID.randomUUID().toString(), new ArrayList<>(), new ArrayList<>());
        }
    }

    /**
     * Готовый датасет для отправки в sidecar.
     * X: матрица [n_samples][n_features]
     * y: массив меток [n_samples]
     */
    public record Dataset(
            String datasetId,
            double[][] X,
            int[] y,
            int samples,
            int features
    ) {}

    public Dataset build(Rows rows) {
        if (rows == null) throw new IllegalArgumentException("rows=null");

        List<double[]> Xrows = rows.Xrows();
        List<Integer> yList = rows.y();

        if (Xrows == null || yList == null) {
            throw new IllegalArgumentException("rows.Xrows/rows.y is null");
        }
        if (Xrows.isEmpty()) {
            throw new IllegalArgumentException("dataset пустой (Xrows=0)");
        }
        if (Xrows.size() != yList.size()) {
            throw new IllegalArgumentException("размеры не совпадают: Xrows=" + Xrows.size() + " y=" + yList.size());
        }

        int n = Xrows.size();
        int f = -1;

        for (int i = 0; i < n; i++) {
            double[] r = Xrows.get(i);
            if (r == null) throw new IllegalArgumentException("Xrows[" + i + "]=null");
            if (f < 0) f = r.length;
            if (r.length != f) {
                throw new IllegalArgumentException("разная длина фич: row=" + i + " len=" + r.length + " expected=" + f);
            }
            Integer lbl = yList.get(i);
            if (lbl == null) throw new IllegalArgumentException("y[" + i + "]=null");
            if (lbl != 0 && lbl != 1) {
                throw new IllegalArgumentException("y[" + i + "] должен быть 0/1, а пришло: " + lbl);
            }
        }

        double[][] X = new double[n][f];
        int[] y = new int[n];

        for (int i = 0; i < n; i++) {
            System.arraycopy(Xrows.get(i), 0, X[i], 0, f);
            y[i] = yList.get(i);
        }

        String id = (rows.datasetId() == null || rows.datasetId().isBlank())
                ? UUID.randomUUID().toString()
                : rows.datasetId().trim();

        log.info("📦 Dataset built: id={} samples={} features={}", id, n, f);

        return new Dataset(id, X, y, n, f);
    }
}
