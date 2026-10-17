package com.playdata.calen.ledger.embedding.sync;

import com.playdata.calen.ledger.application.port.in.SynchronizeLedgerEmbeddingsUseCase;
import com.playdata.calen.common.embedding.EmbeddingProperties;
import com.playdata.calen.common.jobs.WorkLeaseService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@Slf4j
public class LedgerEmbeddingSyncWorker {
    private static final String WORKER_LEASE = "ledger-embedding:api-worker:v1";
    private final EmbeddingProperties apiProperties;
    private final LedgerEmbeddingSyncProperties properties;
    private final SynchronizeLedgerEmbeddingsUseCase batches;
    private final WorkLeaseService leases;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ledger-embedding-sync");
        thread.setDaemon(true);
        return thread;
    });

    public LedgerEmbeddingSyncWorker(EmbeddingProperties apiProperties, LedgerEmbeddingSyncProperties properties,
            SynchronizeLedgerEmbeddingsUseCase batches, WorkLeaseService leases) {
        this.apiProperties = apiProperties;
        this.properties = properties;
        this.batches = batches;
        this.leases = leases;
        properties.validateLeaseBudget(apiProperties);
    }

    @Scheduled(fixedDelayString = "${app.embedding.sync.poll-interval:5s}", initialDelayString = "${app.embedding.sync.poll-interval:5s}")
    public void poll() {
        if (!apiProperties.isEnabled() || !busy.compareAndSet(false, true)) return;
        try {
            // Return immediately so the scheduling pool can keep renewing shared work leases.
            executor.execute(() -> {
                try { runBatch(); }
                catch (RuntimeException failure) { log.warn("Ledger embedding worker cycle failed; durable claims will be retried or recovered."); }
                finally { busy.set(false); }
            });
        } catch (RejectedExecutionException shutdown) { busy.set(false); }
    }

    private void runBatch() {
        if (!apiProperties.isEnabled()) return;
        // One CPU API batch at a time across backend instances; job tokens remain the final DB guard.
        try (WorkLeaseService.Lease lease = leases.tryAcquire(WORKER_LEASE, properties.getLeaseDuration())) {
            if (lease == null) return;
            batches.run(apiProperties.getBatchSize(), new SynchronizeLedgerEmbeddingsUseCase.Execution() {
                @Override public boolean enabled() { return apiProperties.isEnabled(); }
                @Override public boolean leaseValid() { return lease.isValid(); }
                @Override public void assertNetworkReady() {
                    if (TransactionSynchronizationManager.isActualTransactionActive()) {
                        throw new IllegalStateException("embedding network calls must run outside a database transaction");
                    }
                }
                @Override public void reportFailure(String code, int count, boolean retryable) {
                    log.warn("Ledger embedding batch failed: code={}, count={}, retryable={}", code, count, retryable);
                }
            });
        }
    }

    @PreDestroy
    public void stop() { executor.shutdownNow(); }
}
