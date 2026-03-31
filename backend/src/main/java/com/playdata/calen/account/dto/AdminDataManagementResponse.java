package com.playdata.calen.account.dto;

import java.util.List;

public record AdminDataManagementResponse(
        AdminDataStatsResponse stats,
        List<AdminBackupFileResponse> backups,
        String backupsError,
        boolean busy,
        String runningOperation
) {
}
