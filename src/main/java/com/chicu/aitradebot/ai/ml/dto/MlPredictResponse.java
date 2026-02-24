package com.chicu.aitradebot.ai.ml.dto;

import lombok.Data;

@Data
public class MlPredictResponse {

    private boolean ok;

    /** вероятность "BUY" (или “win”), 0..1 */
    private Double proba;

    /** версия модели/артефакта, чтобы логировать/кешировать */
    private String modelVersion;

    /** сообщение об ошибке (если ok=false) */
    private String error;

    /** опционально — таймстемп */
    private Long tsMs;

    // =====================================================
    // ✅ Фабрики (чтобы компилился MlApiController и клиент)
    // =====================================================

    public static MlPredictResponse ok(Double proba, String modelVersion) {
        MlPredictResponse r = new MlPredictResponse();
        r.ok = true;
        r.tsMs = System.currentTimeMillis();
        r.proba = sanitizeProba(proba);
        r.modelVersion = blankToNull(modelVersion);
        return r;
    }

    public static MlPredictResponse fail(String error) {
        MlPredictResponse r = new MlPredictResponse();
        r.ok = false;
        r.tsMs = System.currentTimeMillis();
        r.error = blankToNull(error);
        r.proba = null;
        r.modelVersion = null;
        return r;
    }

    // =====================================================
    // helpers
    // =====================================================

    private static Double sanitizeProba(Double p) {
        if (p == null) return null;
        if (!Double.isFinite(p)) return null;
        if (p < 0.0) return 0.0;
        if (p > 1.0) return 1.0;
        return p;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
