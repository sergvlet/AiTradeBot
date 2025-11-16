package com.chicu.aitradebot;

import com.chicu.aitradebot.util.TimeUtil;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = "com.chicu.aitradebot")
public class AiTradeBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiTradeBotApplication.class, args);
    }
    @Bean(name = "timeUtil")
    public TimeUtil timeUtil() {
        return new TimeUtil();
    }

}
