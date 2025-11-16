package com.chicu.aitradebot.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class TimeUtil {

    public static LocalDateTime fromMillis(Long ts) {
        if (ts == null) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault());
    }
}
