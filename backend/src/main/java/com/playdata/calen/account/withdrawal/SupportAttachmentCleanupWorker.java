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

/** Internal test worker only; it is disabled unless both flags are explicitly enabled. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.account-withdrawal",
        name = {"internal-workflow-enabled", "support-cleanup-worker-enabled"}, havingValue = "true")
public class SupportAttachmentCleanupWorker {
    private final SupportAttachmentCleanupExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "withdrawal-support-cleanup-test-worker");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct void start() { scheduler.scheduleWithFixedDelay(this::runBatch, 10, 10, TimeUnit.SECONDS); }
    @PreDestroy void stop() { scheduler.shutdownNow(); }
    void runBatch() {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (int i = 0; i < 5; i++) {
                var result = executor.processNext();
                if (result == SupportAttachmentCleanupExecutor.Result.NO_TASK
                        || result == SupportAttachmentCleanupExecutor.Result.LOST_LEASE) return;
            }
        } catch (RuntimeException failure) {
            // Never write encrypted task payloads, paths or exception details to logs.
            log.warn("Support attachment cleanup stopped this cycle; tasks remain recoverable.");
        } finally { running.set(false); }
    }
}
