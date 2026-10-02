package com.playdata.calen.ledger.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class LedgerAiAsyncConfig {

    @Bean(name = "ledgerAiTaskExecutor")
    public ThreadPoolTaskExecutor ledgerAiTaskExecutor(
            @Value("${app.ledger.ai.workers:1}") int workers,
            @Value("${app.ledger.ai.queue-capacity:100}") int queueCapacity) {
        if (workers < 1 || queueCapacity < 0) throw new IllegalArgumentException("Invalid AI executor limits");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ledger-ai-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
