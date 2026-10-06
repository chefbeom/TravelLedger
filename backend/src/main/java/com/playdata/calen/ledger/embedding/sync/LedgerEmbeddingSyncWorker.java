package com.playdata.calen.ledger.embedding.sync;

import com.playdata.calen.common.embedding.EmbeddingApiClient;
import com.playdata.calen.common.embedding.EmbeddingApiException;
import com.playdata.calen.common.embedding.EmbeddingProperties;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.embedding.LedgerEmbeddingPointIds;
import com.playdata.calen.ledger.embedding.sync.LedgerEmbeddingSyncStore.Claim;
import com.playdata.calen.ledger.embedding.sync.LedgerEmbeddingSyncStore.Prepared;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
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
    private final LedgerEmbeddingSyncStore store;
    private final EmbeddingApiClient api;
    private final WorkLeaseService leases;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ledger-embedding-sync");
        thread.setDaemon(true);
        return thread;
    });

    public LedgerEmbeddingSyncWorker(EmbeddingProperties apiProperties, LedgerEmbeddingSyncProperties properties,
            LedgerEmbeddingSyncStore store, EmbeddingApiClient api, WorkLeaseService leases) {
        this.apiProperties = apiProperties;
        this.properties = properties;
        this.store = store;
        this.api = api;
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
            store.recoverExpired();
            List<Prepared> upserts = new ArrayList<>();
            List<Prepared> deletes = new ArrayList<>();
            for (Claim claim : store.claimBatch(apiProperties.getBatchSize())) {
                if (!lease.isValid()) return;
                try {
                    Prepared prepared = store.prepareLatest(claim);
                    if (prepared == null) continue;
                    (prepared.document() == null ? deletes : upserts).add(prepared);
                } catch (IllegalArgumentException invalidDocument) { store.failed(claim, false, "INVALID_SOURCE"); }
                catch (RuntimeException sourceFailure) { store.failed(claim, true, "SOURCE_READ_FAILED"); }
            }
            send(upserts, false, lease);
            send(deletes, true, lease);
        }
    }

    private void send(List<Prepared> prepared, boolean delete, WorkLeaseService.Lease lease) {
        if (!lease.isValid() || !apiProperties.isEnabled()) return;
        List<Prepared> current = prepared.stream().filter(item -> {
            if (store.isCurrent(item.claim())) return true;
            store.releaseSuperseded(item.claim());
            return false;
        }).toList();
        if (current.isEmpty() || !lease.isValid()) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("embedding network calls must run outside a database transaction");
        }
        try {
            if (delete) {
                api.deleteDocuments(current.stream().map(item -> LedgerEmbeddingPointIds.forRecord(
                        item.claim().ownerId(), item.claim().type(), item.claim().sourceId())).toList());
            } else {
                api.upsertDocuments(current.stream().map(Prepared::document).toList());
            }
        } catch (EmbeddingApiException failure) {
            String code = failure.getHttpStatus() == null
                    ? (failure.isRetryable() ? "API_UNAVAILABLE" : "API_CONTRACT_ERROR") : "HTTP_" + failure.getHttpStatus();
            failBatch(current, failure.isRetryable(), code, lease);
            return;
        } catch (IllegalArgumentException invalidRequest) {
            failBatch(current, false, "INVALID_DOCUMENT", lease);
            return;
        } catch (RuntimeException unknownFailure) {
            failBatch(current, true, "API_CALL_FAILED", lease);
            return;
        }
        // Completion cannot acknowledge a new source revision. New edits remain pending.
        if (lease.isValid()) for (Prepared item : current) store.succeeded(item.claim());
    }

    private void failBatch(List<Prepared> batch, boolean retryable, String code, WorkLeaseService.Lease lease) {
        if (!lease.isValid()) return;
        for (Prepared item : batch) store.failed(item.claim(), retryable, code);
        log.warn("Ledger embedding batch failed: code={}, count={}, retryable={}", code, batch.size(), retryable);
    }

    @PreDestroy
    public void stop() { executor.shutdownNow(); }
}
