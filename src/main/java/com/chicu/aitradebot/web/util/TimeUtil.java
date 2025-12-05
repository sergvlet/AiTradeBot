package com.chicu.aitradebot.web.util;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Component("timeUtil") // 🔥 именно это имя используется в Thymeleaf
public class TimeUtil {

    public LocalDateTime fromMillis(Long ms) {
        if (ms == null) return null;
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(ms),
                ZoneId.systemDefault()
        );
    }
}
