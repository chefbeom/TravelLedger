package com.playdata.calen.travel.service;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.jobs.WorkLeaseService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Persistent generations merge upload bursts and prevent stale calculations from being saved. */
@Service @Slf4j
public class TravelClusterRefreshCoordinator {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;
    private final WorkLeaseService leases;
    private final ObjectProvider<TravelService> services;
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> tasks = new ConcurrentHashMap<>();
    private volatile long recoveryOwnerCursor;

    public TravelClusterRefreshCoordinator(DataSource dataSource, PlatformTransactionManager manager,
            WorkLeaseService leases, ObjectProvider<TravelService> services) {
        jdbc = new JdbcTemplate(dataSource); this.leases = leases; this.services = services;
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("photo-cluster-refresh-"); executor.setAwaitTerminationSeconds(20); executor.initialize();
    }

    public void markDirty(Long userId) {
        writes.executeWithoutResult(transaction -> markDirtyInCurrentTransaction(userId));
        dispatch(userId);
    }

    /** Dirty state commits atomically with the media change. No calculation runs in this transaction. */
    public void markDirtyInCurrentTransaction(Long userId) {
        int updated = jdbc.update("update travel_photo_cluster_refresh_jobs set generation = generation + 1, dirty = true where owner_id = ?", userId);
        if (updated == 0) {
            try { jdbc.update("insert into travel_photo_cluster_refresh_jobs (owner_id, generation, processed_generation, dirty) values (?, 1, 0, true)", userId); }
            catch (DuplicateKeyException race) { jdbc.update("update travel_photo_cluster_refresh_jobs set generation = generation + 1, dirty = true where owner_id = ?", userId); }
        }
    }

    public void dispatch(Long userId) {
        kick(userId);
    }

    /** Called before the read transaction; followers wait without holding a DB connection. */
    public long ensureCurrent(Long userId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Status status = status(userId, false);
            if (status == null) { markDirty(userId); continue; }
            if (!status.dirty()) return status.processedGeneration();
            try {
                kick(userId).get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                Status updated = status(userId, false);
                if (updated != null && !updated.dirty()) return updated.processedGeneration();
                Thread.sleep(100);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new ServiceUnavailableException("사진 지도 갱신 대기가 중단되었습니다.");
            } catch (Exception failure) {
                throw new ServiceUnavailableException("사진 지도를 갱신 중입니다. 잠시 후 다시 조회해 주세요.");
            }
        }
        throw new ServiceUnavailableException("사진 지도를 갱신 중입니다. 잠시 후 다시 조회해 주세요.");
    }

    private CompletableFuture<Void> kick(Long userId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        var existing = tasks.putIfAbsent(userId, future);
        if (existing != null) return existing;
        try {
            executor.execute(() -> {
                try { process(userId); future.complete(null); }
                catch (Throwable failure) { future.completeExceptionally(failure); log.warn("Photo cluster refresh failed: userId={}", userId, failure); }
                finally { tasks.remove(userId, future); }
            });
        } catch (RuntimeException capacity) {
            tasks.remove(userId, future);
            future.completeExceptionally(capacity);
        }
        return future;
    }

    private void process(Long userId) {
        try (var lease = leases.tryAcquire("photo-cluster-refresh:" + userId, Duration.ofMinutes(2))) {
            if (lease == null) return;
            for (int attempt = 0; attempt < 3; attempt++) {
                Status initial = status(userId, false);
                if (initial == null || !initial.dirty() || !lease.isValid()) return;
                // The service reads small point DTOs in a short transaction, then calculates outside it.
                var clusters = services.getObject().computeFreshPhotoClusters(userId);
                if (!lease.isValid()) return;
                writes.executeWithoutResult(transaction -> {
                    Status latest = status(userId, true);
                    if (latest == null || latest.generation() != initial.generation() || !lease.isValid()) return;
                    services.getObject().applyFreshPhotoClusters(userId, clusters);
                    jdbc.update("update travel_photo_cluster_refresh_jobs set processed_generation = generation, dirty = false where owner_id = ?", userId);
                });
            }
        }
    }

    /** Serializes manual representative changes with the background writer in the caller's transaction. */
    public void lockManualRefresh(Long userId) {
        Status status = status(userId, true);
        if (status == null) {
            markDirtyInCurrentTransaction(userId);
            status(userId, true);
        } else jdbc.update("update travel_photo_cluster_refresh_jobs set generation = generation + 1, dirty = true where owner_id = ?", userId);
    }

    private Status status(Long userId, boolean lock) {
        var rows = jdbc.query("select generation, processed_generation, dirty from travel_photo_cluster_refresh_jobs where owner_id = ?" + (lock ? " for update" : ""),
                (row, index) -> new Status(row.getLong(1), row.getLong(2), row.getBoolean(3)), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void recover() {
        var owners = jdbc.queryForList("select owner_id from travel_photo_cluster_refresh_jobs where dirty = true and owner_id > ? order by owner_id limit 100",
                Long.class, recoveryOwnerCursor);
        owners.forEach(this::kick);
        recoveryOwnerCursor = owners.size() < 100 ? 0 : owners.get(owners.size() - 1);
    }

    @PreDestroy void shutdown() { executor.shutdown(); }
    private record Status(long generation, long processedGeneration, boolean dirty) { }
}
