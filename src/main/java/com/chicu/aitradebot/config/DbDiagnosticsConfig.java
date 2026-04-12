package com.chicu.aitradebot.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class DbDiagnosticsConfig {

    private final DataSource dataSource;

    @Bean
    public ApplicationRunner dbDiagnosticsRunner() {
        return args -> {
            try (Connection c = dataSource.getConnection()) {
                DatabaseMetaData md = c.getMetaData();
                log.info("🗄️ DB meta: url={} driver={} {} user={}",
                        md.getURL(),
                        md.getDriverName(),
                        md.getDriverVersion(),
                        md.getUserName()
                );
                log.info("🗄️ DB tx: autoCommit={} isolation={}",
                        c.getAutoCommit(),
                        c.getTransactionIsolation()
                );
            } catch (Exception e) {
                log.warn("⚠ DB diagnostics failed: {}", e.getMessage());
            }
        };
    }
}
