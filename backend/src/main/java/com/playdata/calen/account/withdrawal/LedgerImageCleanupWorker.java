package com.playdata.calen.account.withdrawal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Isolated test worker; the public withdrawal flow and production cleanup are not enabled. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.account-withdrawal",
        name = {"internal-workflow-enabled", "image-cleanup-worker-enabled"}, havingValue = "true")
public class LedgerImageCleanupWorker {
    private static final int MAX_PER_CYCLE = 5;
    private final LedgerImageCleanupExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "withdrawal-image-cleanup-test-worker");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct void start() { scheduler.scheduleWithFixedDelay(this::runBatch, 10, 10, TimeUnit.SECONDS); }
    @PreDestroy void stop() { scheduler.shutdownNow(); }

    void runBatch() {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (int i = 0; i < MAX_PER_CYCLE; i++) {
                var result = executor.processNext();
                if (result == LedgerImageCleanupExecutor.Result.NO_TASK
                        || result == LedgerImageCleanupExecutor.Result.CONFIGURATION_REQUIRED
                        || result == LedgerImageCleanupExecutor.Result.LOST_LEASE) return;
            }
        } catch (RuntimeException failure) {
            // Provider errors may contain private paths, metadata or credentials.
            log.warn("Ledger image cleanup stopped this cycle; durable tasks remain recoverable.");
        } finally {
            running.set(false);
        }
    }
}
