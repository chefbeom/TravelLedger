package com.playdata.calen.ledger.ocr;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class LedgerOcrAsyncConfig {

    @Bean(name = "ledgerOcrTaskExecutor")
    public ThreadPoolTaskExecutor ledgerOcrTaskExecutor(
            @Value("${app.ledger.ocr.workers:1}") int workers,
            @Value("${app.ledger.ocr.queue-capacity:100}") int queueCapacity) {
        if (workers < 1 || queueCapacity < 0) throw new IllegalArgumentException("Invalid image analysis executor limits");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ledger-ocr-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
