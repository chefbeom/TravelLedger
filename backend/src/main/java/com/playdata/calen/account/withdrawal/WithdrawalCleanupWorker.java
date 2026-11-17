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

/** Test-only bounded polling: public withdrawal and all mandatory cleanup are not ready. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.account-withdrawal",
        name = {"internal-workflow-enabled", "cleanup-worker-enabled"}, havingValue = "true")
public class WithdrawalCleanupWorker {
    private static final int MAX_PER_CYCLE = 5;
    private final KakaoWithdrawalCleanupExecutor kakao;
    private final AtomicBoolean running = new AtomicBoolean();
    // Keep slow provider calls off the application's shared scheduling thread.
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "withdrawal-cleanup-test-worker");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    void start() {
        scheduler.scheduleWithFixedDelay(this::runBatch, 10, 10, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        // A killed/interrupted request remains recoverable through its durable lease.
        scheduler.shutdownNow();
    }

    void runBatch() {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (int index = 0; index < MAX_PER_CYCLE; index++) {
                var result = kakao.processNext();
                if (result == KakaoWithdrawalCleanupExecutor.Result.NO_TASK
                        || result == KakaoWithdrawalCleanupExecutor.Result.CONFIGURATION_REQUIRED
                        || result == KakaoWithdrawalCleanupExecutor.Result.LOST_LEASE) return;
            }
        } catch (RuntimeException unconfirmed) {
            // Exceptions may contain provider credentials or account identifiers.
            log.warn("Withdrawal cleanup worker stopped this cycle; durable tasks will be retried.");
        } finally {
            running.set(false);
        }
    }
}
