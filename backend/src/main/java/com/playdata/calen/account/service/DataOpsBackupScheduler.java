package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.repository.AppUserRepository;
import jakarta.annotation.PostConstruct;
import java.util.List;
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
    private final AppUserRepository appUserRepository;
    private final UserNotificationService userNotificationService;

    @Value("${app.data-ops.db-backup-enabled:false}")
    private boolean databaseBackupEnabled;

    @Value("${app.data-ops.minio-backup-enabled:false}")
    private boolean minioBackupEnabled;

    @Value("${app.data-ops.db-backup-cron:0 0 0 * * *}")
    private String databaseBackupCron;

    @Value("${app.data-ops.minio-backup-cron:0 30 0 * * *}")
    private String minioBackupCron;

    @Value("${app.data-ops.backup-zone:Asia/Seoul}")
    private String backupZone;

    @PostConstruct
    void logSchedulerConfiguration() {
        log.info(
                "Data ops backup scheduler configured: dbBackupEnabled={}, dbBackupCron='{}', minioBackupEnabled={}, minioBackupCron='{}', zone='{}'",
                databaseBackupEnabled,
                databaseBackupCron,
                minioBackupEnabled,
                minioBackupCron,
                backupZone
        );
    }

    @Scheduled(cron = "${app.data-ops.db-backup-cron:0 0 0 * * *}", zone = "${app.data-ops.backup-zone:Asia/Seoul}")
    public void runScheduledDatabaseBackup() {
        if (!databaseBackupEnabled) {
            return;
        }

        try {
            adminDataManagementService.createManualBackup();
            log.info("Scheduled database backup completed successfully.");
        } catch (Exception exception) {
            log.warn("Scheduled database backup failed.", exception);
            notifyBackupFailed("database");
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
            log.warn("Scheduled MinIO backup failed.", exception);
            notifyBackupFailed("minio");
        }
    }

    private void notifyBackupFailed(String backupType) {
        List<AppUser> admins = appUserRepository.findAllByRoleAndActiveTrueOrderByIdAsc(AppUserRole.ADMIN);
        for (AppUser admin : admins) {
            if (admin.getId() == null) {
                continue;
            }
            try {
                userNotificationService.createSystemNotification(
                        admin.getId(),
                        "BACKUP_FAILED",
                        "Scheduled backup failed",
                        "A scheduled " + backupType + " backup failed. Check data-management logs before the next release window.",
                        "/admin?panel=data-management",
                        "{\"backupType\":\"" + backupType + "\",\"status\":\"failure\"}"
                );
            } catch (RuntimeException notificationException) {
                log.warn("Failed to create backup failure notification: backupType={}, adminId={}", backupType, admin.getId(), notificationException);
            }
        }
    }
}