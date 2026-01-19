package com.chicu.aitradebot.ai.ml.features;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public class FeatureSchema {

    private final String[] names;
    private final String schemaHash;

    public FeatureSchema(String[] names) {
        if (names == null || names.length == 0) {
            throw new IllegalArgumentException("schema names пустые");
        }
        this.names = names.clone();
        this.schemaHash = sha256(String.join("|", this.names));
    }

    // ✅ если где-то у тебя уже используется List<String>
    public FeatureSchema(List<String> names) {
        this(names != null ? names.toArray(new String[0]) : null);
    }

    public String[] featureNames() {
        return names.clone();
    }

    // ✅ это и просит твой MlTrainingService
    public String schemaHash() {
        return schemaHash;
    }

    public double[] toVector(Map<String, Double> features) {
        double[] x = new double[names.length];
        for (int i = 0; i < names.length; i++) {
            String k = names[i];
            Double v = features != null ? features.get(k) : null;
            x[i] = (v != null && Double.isFinite(v)) ? v : 0.0;
        }
        return x;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("sha256 error: " + e.getMessage(), e);
        }
    }
}
