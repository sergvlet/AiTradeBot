package com.chicu.aitradebot.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.orm.jpa.JpaTransactionManager;

@Configuration
@EnableTransactionManagement
public class JpaTxConfig {

    /**
     * ✅ КРИТИЧНО:
     * Если где-то в проекте есть кастомный transactionManager (например DataSourceTransactionManager),
     * то JPA-операции @Modifying могут выполняться без JPA транзакции → TransactionRequiredException.
     *
     * Этот бин делает JpaTransactionManager главным.
     */
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}