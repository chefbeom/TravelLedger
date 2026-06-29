package com.playdata.calen.ledger.ai;

import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class LedgerAiAnalysisHistoryRetentionService {

    private static final int MIN_RETENTION_DAYS = 1;

    private final LedgerAiAnalysisHistoryRepository historyRepository;
    private final boolean retentionEnabled;
    private final int retentionDays;
    private final String retentionCron;
    private final String retentionZone;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public LedgerAiAnalysisHistoryRetentionService(
            LedgerAiAnalysisHistoryRepository historyRepository,
            @Value("${app.ledger.ai.history-retention-enabled:false}") boolean retentionEnabled,
            @Value("${app.ledger.ai.history-retention-days:180}") int retentionDays,
            @Value("${app.ledger.ai.history-retention-cron:0 15 3 * * *}") String retentionCron,
            @Value("${app.ledger.ai.history-retention-zone:Asia/Seoul}") String retentionZone
    ) {
        this.historyRepository = historyRepository;
        this.retentionEnabled = retentionEnabled;
        this.retentionDays = retentionDays;
        this.retentionCron = retentionCron;
        this.retentionZone = retentionZone;
    }

    @PostConstruct
    void logRetentionConfiguration() {
        log.info(
                "Ledger AI history retention configured: enabled={}, days={}, cron='{}', zone='{}'",
                retentionEnabled,
                normalizedRetentionDays(),
                retentionCron,
                retentionZone
        );
    }

    @Scheduled(
            cron = "${app.ledger.ai.history-retention-cron:0 15 3 * * *}",
            zone = "${app.ledger.ai.history-retention-zone:Asia/Seoul}"
    )
    @Transactional
    public void runScheduledRetentionCleanup() {
        if (!retentionEnabled) {
            return;
        }
        try {
            int deletedCount = deleteExpiredHistories(LocalDateTime.now());
            recordRetentionRun("success");
            if (deletedCount > 0) {
                log.info("Deleted {} expired ledger AI analysis history rows.", deletedCount);
            }
        } catch (RuntimeException exception) {
            recordRetentionRun("failure");
            log.warn("Scheduled ledger AI history retention cleanup failed.", exception);
        }
    }

    @Transactional
    public int deleteExpiredHistories(LocalDateTime now) {
        LocalDateTime baseTime = now == null ? LocalDateTime.now() : now;
        LocalDateTime cutoff = baseTime.minusDays(normalizedRetentionDays());
        return historyRepository.deleteByCreatedAtBefore(cutoff);
    }

    private void recordRetentionRun(String status) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("calen.ledger.ai.history.retention.runs")
                .description("Ledger AI history retention cleanup runs")
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    private int normalizedRetentionDays() {
        return Math.max(MIN_RETENTION_DAYS, retentionDays);
    }
}