package com.playdata.calen.account.dto;

import java.util.List;

public record AdminDataManagementResponse(
        AdminDataStatsResponse stats,
        List<AdminBackupFileResponse> backups,
        String backupsError,
        AdminMinioStorageSummaryResponse minioStorage,
        List<AdminBackupFileResponse> minioBackups,
        String minioBackupsError,
        boolean busy,
        String runningOperation
) {
}
