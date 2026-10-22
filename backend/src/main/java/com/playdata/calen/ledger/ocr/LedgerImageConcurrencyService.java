package com.playdata.calen.ledger.ocr;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.dto.LedgerImageConcurrencyResponse;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runtime image-only limit; the existing upstream provider safety limit still applies. */
@Service
@DependsOn("adminOpsControlService")
@Slf4j
public class LedgerImageConcurrencyService {
    static final String SETTING_KEY = "image-analysis.max-concurrent";
    public static final int DEFAULT_CONCURRENT = 3;
    public static final int MAX_CONCURRENT = 20;
    private final JdbcTemplate jdbc;
    private final ThreadPoolTaskExecutor executor;
    private final WorkLeaseService leases;
    private volatile int currentLimit = DEFAULT_CONCURRENT;

    public LedgerImageConcurrencyService(JdbcTemplate jdbc,
            @Qualifier("ledgerOcrTaskExecutor") ThreadPoolTaskExecutor executor, WorkLeaseService leases) {
        this.jdbc = jdbc;
        this.executor = executor;
        this.leases = leases;
    }

    @PostConstruct
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public synchronized void refresh() {
        try {
            var values = jdbc.query("select setting_value from admin_ops_control_settings where setting_key = ?",
                    (row, index) -> row.getString(1), SETTING_KEY);
            int limit = values.isEmpty() ? DEFAULT_CONCURRENT : Integer.parseInt(values.get(0));
            applyLimit(limit >= 1 && limit <= MAX_CONCURRENT ? limit : DEFAULT_CONCURRENT);
        } catch (DataAccessException | NumberFormatException failure) {
            // Retain the last known limit during a temporary configuration-store outage.
            applyLimit(currentLimit);
            log.warn("Image concurrency settings could not be refreshed; keeping the last known limit.");
        }
    }

    public LedgerImageConcurrencyResponse getSettings() {
        refresh();
        return response(currentLimit);
    }

    @Transactional
    public LedgerImageConcurrencyResponse update(Integer limit) {
        if (limit == null || limit < 1 || limit > MAX_CONCURRENT) {
            throw new BadRequestException("이미지 동시 분석 수는 1~20 사이의 정수로 입력해 주세요.");
        }
        try {
            int updated = jdbc.update("update admin_ops_control_settings set setting_value = ?, updated_at = current_timestamp where setting_key = ?", limit.toString(), SETTING_KEY);
            if (updated == 0) {
                try {
                    jdbc.update("insert into admin_ops_control_settings (setting_key, setting_value, updated_at) values (?, ?, current_timestamp)", SETTING_KEY, limit.toString());
                } catch (DuplicateKeyException concurrentInsert) {
                    jdbc.update("update admin_ops_control_settings set setting_value = ?, updated_at = current_timestamp where setting_key = ?", limit.toString(), SETTING_KEY);
                }
            }
        } catch (DataAccessException failure) {
            throw new ServiceUnavailableException("동시 분석 수를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { refresh(); }
            });
        } else {
            refresh();
        }
        return response(limit);
    }

    private synchronized void applyLimit(int limit) {
        // Increase max before core, decrease core before max; do not interrupt running jobs.
        if (limit > executor.getMaxPoolSize()) executor.setMaxPoolSize(limit);
        executor.setCorePoolSize(limit);
        executor.setMaxPoolSize(limit);
        currentLimit = limit;
    }

    public <T> T execute(Supplier<T> action) {
        refresh();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                for (int slot = 0; slot < currentLimit; slot++) {
                    WorkLeaseService.Lease lease = leases.tryAcquire("image-analysis-slot:" + slot, Duration.ofMinutes(30));
                    if (lease != null) {
                        try (lease) {
                            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Image analysis cancelled.");
                            return action.get();
                        }
                    }
                }
                Thread.sleep(200);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        throw new CancellationException("Image analysis cancelled while waiting for a slot.");
    }

    private LedgerImageConcurrencyResponse response(int limit) {
        return new LedgerImageConcurrencyResponse(limit, 1, MAX_CONCURRENT);
    }
}
