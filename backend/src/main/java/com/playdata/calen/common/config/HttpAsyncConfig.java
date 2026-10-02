package com.playdata.calen.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class HttpAsyncConfig implements WebMvcConfigurer {
    @Value("${app.http.async.workers:8}")
    private int workers = 8;
    @Value("${app.http.async.queue-capacity:64}")
    private int queueCapacity = 64;
    @Value("${app.http.async.timeout-millis:600000}")
    private long timeoutMillis = 600000;

    @Bean(name = "applicationTaskExecutor")
    public ThreadPoolTaskExecutor applicationTaskExecutor() {
        if (workers < 1 || queueCapacity < 0 || timeoutMillis < 1) throw new IllegalArgumentException("Invalid HTTP async limits");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("http-stream-");
        executor.setAwaitTerminationSeconds(30);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(applicationTaskExecutor()).setDefaultTimeout(timeoutMillis);
    }

    @Bean(name = "travelMapReadExecutor")
    public ThreadPoolTaskExecutor travelMapReadExecutor(
            @Value("${app.http.map-workers:4}") int mapWorkers,
            @Value("${app.http.map-queue-capacity:32}") int mapQueueCapacity) {
        if (mapWorkers < 1 || mapQueueCapacity < 0) throw new IllegalArgumentException("Invalid map read limits");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(mapWorkers); executor.setMaxPoolSize(mapWorkers); executor.setQueueCapacity(mapQueueCapacity);
        executor.setThreadNamePrefix("travel-map-read-"); executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
