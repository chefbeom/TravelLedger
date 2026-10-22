package com.playdata.calen.common.jobs;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Database-backed leases share worker ownership and upstream limits across JVMs. */
@Service
@Slf4j
public class WorkLeaseService {
    private static final int MAX_CLAIM_ATTEMPTS = 3;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ConcurrentHashMap<String, Lease> active = new ConcurrentHashMap<>();

    public WorkLeaseService(DataSource dataSource, PlatformTransactionManager transactionManager) {
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // A missing-row UPDATE under InnoDB REPEATABLE READ locks the index gap; parallel
        // INSERTs then deadlock even for different keys. Only these short lease transactions
        // use READ COMMITTED; the unique key and conditional UPDATE still arbitrate ownership.
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Lease tryAcquire(String key, Duration ttl) {
        String token = claimWithRetry(key, ttl);
        if (token == null) return null;
        Lease lease = new Lease(key, token, ttl);
        active.put(key, lease);
        return lease;
    }

    /** Reserves a start slot until expiry; it is deliberately not renewed or released. */
    public boolean tryReserveInterval(String key, Duration interval) {
        return claimWithRetry(key, interval) != null;
    }

    private String claimWithRetry(String key, Duration ttl) {
        for (int attempt = 1; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Work lease acquisition cancelled.");
            try {
                return transactions.execute(status -> claim(key, ttl));
            } catch (DataAccessException | TransactionException failure) {
                boolean conflict = isLockConflict(failure);
                // Retry outside execute(): the failed attempt must be rolled back first.
                if (conflict && attempt < MAX_CLAIM_ATTEMPTS) {
                    try {
                        Thread.sleep(20L * attempt + ThreadLocalRandom.current().nextInt(20));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Work lease acquisition cancelled while retrying.");
                    }
                    continue;
                }
                SQLException sql = sqlCause(failure);
                // Do not log SQL, parameters, lease keys, or ownership tokens.
                log.warn("Work lease acquisition failed: attempts={}, lockConflict={}, failureType={}, sqlState={}, vendorCode={}",
                        attempt, conflict, failure.getClass().getSimpleName(),
                        sql == null ? null : sql.getSQLState(), sql == null ? null : sql.getErrorCode());
                throw new ServiceUnavailableException(conflict
                        ? "작업 소유권 확보가 지연되고 있습니다. 잠시 후 다시 시도해 주세요."
                        : "작업 소유권 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.", failure);
            }
        }
    }

    private boolean isLockConflict(RuntimeException failure) {
        if (failure instanceof PessimisticLockingFailureException) return true;
        SQLException sql = sqlCause(failure);
        return sql != null && ("40001".equals(sql.getSQLState()) || "41000".equals(sql.getSQLState())
                || sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205);
    }

    private SQLException sqlCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql;
            if (cause == cause.getCause()) break;
        }
        return null;
    }

    private String claim(String key, Duration ttl) {
        if (key == null || key.length() > 240 || ttl == null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("Invalid lease.");
        String token = UUID.randomUUID().toString();
        long now = databaseNow();
        long expires = Math.addExact(now, ttl.toMillis());
        int updated = jdbc.update("update app_work_leases set owner_token = ?, expires_at_epoch_millis = ? where lease_key = ? and expires_at_epoch_millis <= ?",
                token, expires, key, now);
        if (updated == 1) return token;
        try {
            jdbc.update("insert into app_work_leases (lease_key, owner_token, expires_at_epoch_millis) values (?, ?, ?)", key, token, expires);
            return token;
        } catch (DuplicateKeyException alreadyClaimed) {
            return null;
        }
    }

    private long databaseNow() {
        return jdbc.queryForObject("select current_timestamp", (row, index) -> row.getTimestamp(1).getTime());
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    void renewActiveLeases() {
        for (Lease lease : active.values()) {
            if (!lease.valid.get()) continue;
            try {
                Integer updated = transactions.execute(status -> {
                    long now = databaseNow();
                    return jdbc.update("update app_work_leases set expires_at_epoch_millis = ? where lease_key = ? and owner_token = ? and expires_at_epoch_millis > ?",
                            now + lease.ttl.toMillis(), lease.key, lease.token, now);
                });
                if (updated == 0) lease.lose();
                else lease.lastRenewedNanos = System.nanoTime();
            } catch (RuntimeException failure) {
                if (System.nanoTime() - lease.lastRenewedNanos >= lease.ttl.toNanos()) lease.lose();
                log.warn("Work lease heartbeat could not reach the database.");
            }
        }
    }

    public final class Lease implements AutoCloseable {
        private final String key;
        private final String token;
        private final Duration ttl;
        private final AtomicBoolean valid = new AtomicBoolean(true);
        private volatile long lastRenewedNanos = System.nanoTime();
        private Lease(String key, String token, Duration ttl) { this.key = key; this.token = token; this.ttl = ttl; }
        public boolean isValid() { return valid.get() && System.nanoTime() - lastRenewedNanos < ttl.toNanos(); }
        private void lose() { valid.set(false); active.remove(key, this); }
        @Override public void close() {
            valid.set(false);
            active.remove(key, this);
            try {
                transactions.executeWithoutResult(status -> jdbc.update("delete from app_work_leases where lease_key = ? and owner_token = ?", key, token));
            } catch (RuntimeException failure) {
                log.warn("Work lease release could not reach the database; expiry will release it.");
            }
        }
    }
}
