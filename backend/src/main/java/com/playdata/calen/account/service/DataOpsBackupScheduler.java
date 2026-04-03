package com.playdata.calen.account.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataOpsBackupScheduler {

    private final AdminDataManagementService adminDataManagementService;

    @Value("${app.data-ops.db-backup-enabled:false}")
    private boolean databaseBackupEnabled;

    @Value("${app.data-ops.minio-backup-enabled:false}")
    private boolean minioBackupEnabled;

    @Scheduled(cron = "${app.data-ops.db-backup-cron:0 0 0 * * *}", zone = "${app.data-ops.backup-zone:Asia/Seoul}")
    public void runScheduledDatabaseBackup() {
        if (!databaseBackupEnabled) {
            return;
        }

        try {
            adminDataManagementService.createManualBackup();
            log.info("Scheduled database backup completed successfully.");
        } catch (Exception exception) {
            log.warn("Scheduled database backup failed: {}", exception.getMessage());
        }
    }

    @Scheduled(cron = "${app.data-ops.minio-backup-cron:0 30 0 * * *}", zone = "${app.data-ops.backup-zone:Asia/Seoul}")
    public void runScheduledMinioBackup() {
        if (!minioBackupEnabled) {
            return;
        }

        try {
            adminDataManagementService.createManualMinioBackup();
            log.info("Scheduled MinIO backup completed successfully.");
        } catch (Exception exception) {
            log.warn("Scheduled MinIO backup failed: {}", exception.getMessage());
        }
    }
}
