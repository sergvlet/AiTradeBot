package com.chicu.aitradebot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.chicu.aitradebot")
public class AiTradeBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiTradeBotApplication.class, args);
    }

}
