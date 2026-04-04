package com.playdata.calen.account.service;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DataOpsBackupSchedulerTest {

    @Test
    void skipsDatabaseBackupWhenDisabled() throws Exception {
        AdminDataManagementService adminDataManagementService = mock(AdminDataManagementService.class);
        DataOpsBackupScheduler scheduler = new DataOpsBackupScheduler(adminDataManagementService);

        setBooleanField(scheduler, "databaseBackupEnabled", false);

        scheduler.runScheduledDatabaseBackup();

        verify(adminDataManagementService, never()).createManualBackup();
    }

    @Test
    void runsDatabaseBackupWhenEnabled() throws Exception {
        AdminDataManagementService adminDataManagementService = mock(AdminDataManagementService.class);
        DataOpsBackupScheduler scheduler = new DataOpsBackupScheduler(adminDataManagementService);

        setBooleanField(scheduler, "databaseBackupEnabled", true);

        scheduler.runScheduledDatabaseBackup();

        verify(adminDataManagementService, times(1)).createManualBackup();
    }

    @Test
    void runsMinioBackupWhenEnabled() throws Exception {
        AdminDataManagementService adminDataManagementService = mock(AdminDataManagementService.class);
        DataOpsBackupScheduler scheduler = new DataOpsBackupScheduler(adminDataManagementService);

        setBooleanField(scheduler, "minioBackupEnabled", true);

        scheduler.runScheduledMinioBackup();

        verify(adminDataManagementService, times(1)).createManualMinioBackup();
    }

    private void setBooleanField(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }
}
