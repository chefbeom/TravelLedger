package com.playdata.calen.travel.adapter.out.cluster;

import com.playdata.calen.travel.service.TravelClusterRefreshCoordinator;
import java.util.HashSet;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shares one transactional dirty/dispatch batch across migrated plan writes and legacy media writes. */
public final class TravelClusterRefreshRequests {
    private TravelClusterRefreshRequests() {}

    public static void enqueue(TravelClusterRefreshCoordinator coordinator, Long owner) {
        if (coordinator == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { coordinator.markDirty(owner); return; }
        var batch = TransactionSynchronizationManager.getSynchronizations().stream().filter(Batch.class::isInstance).map(Batch.class::cast)
                .findFirst().orElse(null);
        if (batch == null) {
            batch = new Batch(coordinator);
            TransactionSynchronizationManager.registerSynchronization(batch);
        }
        if (batch.owners.add(owner)) coordinator.markDirtyInCurrentTransaction(owner);
    }

    private static final class Batch implements TransactionSynchronization {
        private final TravelClusterRefreshCoordinator coordinator;
        private final Set<Long> owners = new HashSet<>();
        private Batch(TravelClusterRefreshCoordinator coordinator) { this.coordinator = coordinator; }
        @Override public void afterCommit() { owners.forEach(coordinator::dispatch); }
    }
}
