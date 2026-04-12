package com.chicu.aitradebot.ai.ml.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

public final class MlSchemaHash {

    private MlSchemaHash() {}

    public static String sha1PipeJoin(List<String> schema) {
        try {
            String s = String.join("|", schema == null ? List.of() : schema);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return toHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("sha1 failed", e);
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}