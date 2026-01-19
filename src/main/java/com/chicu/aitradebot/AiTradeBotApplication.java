package com.chicu.aitradebot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;
@ConfigurationPropertiesScan
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.chicu.aitradebot")
public class AiTradeBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiTradeBotApplication.class, args);
    }

}
