package com.playdata.calen.account.withdrawal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded retries also process legacy-node writes after a rolling deployment. */
@Component
@RequiredArgsConstructor
@Slf4j
public class UnknownLoginAuditPrivacyWorker {
    private static final int BATCH_SIZE = 250;
    private static final int MAX_BATCHES = 10;
    private final UnknownLoginAuditPrivacyBackfill backfill;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "unknown-login-audit-privacy");
        thread.setDaemon(true);
        return thread;
    });
    @Value("${app.account.unknown-login-audit-anonymization.enabled:true}")
    private boolean enabled;

    @PostConstruct
    void start() {
        if (enabled) scheduler.scheduleWithFixedDelay(this::runBatch, 60, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() { scheduler.shutdownNow(); }

    public void runBatch() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            for (int index = 0; index < MAX_BATCHES; index++) {
                if (backfill.classifyBatch(BATCH_SIZE) < BATCH_SIZE) break;
            }
        } catch (RuntimeException failure) {
            // Do not log login IDs, SQL parameters or exception messages.
            log.warn("Unknown-login audit anonymization failed ({}); it will be retried.", failure.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }
}
