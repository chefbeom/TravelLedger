package com.playdata.calen.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

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

    @Test
    void defaultsToKstMorningBackupSchedules() throws Exception {
        DataOpsBackupScheduler scheduler = new DataOpsBackupScheduler(mock(AdminDataManagementService.class));

        assertThat(getField(scheduler, "databaseBackupCron")).isEqualTo("0 0 6 * * *");
        assertThat(getField(scheduler, "minioBackupCron")).isEqualTo("0 30 6 * * *");
        assertThat(getField(scheduler, "backupZone")).isEqualTo("Asia/Seoul");
    }


    private void setBooleanField(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

}
