package com.playdata.calen.account.service;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.repository.AppUserRepository;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataOpsBackupSchedulerTest {

    @Test
    void skipsDatabaseBackupWhenDisabled() throws Exception {
        AdminDataManagementService adminDataManagementService = mock(AdminDataManagementService.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        UserNotificationService userNotificationService = mock(UserNotificationService.class);
        DataOpsBackupScheduler scheduler = new DataOpsBackupScheduler(
                adminDataManagementService,
                appUserRepository,
                userNotificationService
        );

        setBooleanField(scheduler, "databaseBackupEnabled", false);

        scheduler.runScheduledDatabaseBackup();

        verify(adminDataManagementService, never()).createManualBackup();
        verifyNoInteractions(appUserRepository, userNotificationService);
    }

    @Test
    void runsDatabaseBackupWhenEnabled() throws Exception {
        AdminDataManagementService adminDataManagementService = mock(AdminDataManagementService.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        UserNotificationService userNotificationService = mock(UserNotificationService.class);
        DataOpsBackupScheduler scheduler = new DataOpsBackupScheduler(
                adminDataManagementService,
                appUserRepository,
                userNotificationService
        );

        setBooleanField(scheduler, "databaseBackupEnabled", true);

        scheduler.runScheduledDatabaseBackup();

        verify(adminDataManagementService, times(1)).createManualBackup();
        verifyNoInteractions(appUserRepository, userNotificationService);
    }

    @Test
    void runsMinioBackupWhenEnabled() throws Exception {
        AdminDataManagementService adminDataManagementService = mock(AdminDataManagementService.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        UserNotificationService userNotificationService = mock(UserNotificationService.class);
        DataOpsBackupScheduler scheduler = new DataOpsBackupScheduler(
                adminDataManagementService,
                appUserRepository,
                userNotificationService
        );

        setBooleanField(scheduler, "minioBackupEnabled", true);

        scheduler.runScheduledMinioBackup();

        verify(adminDataManagementService, times(1)).createManualMinioBackup();
        verifyNoInteractions(appUserRepository, userNotificationService);
    }

    @Test
    void notifiesActiveAdminsWhenScheduledDatabaseBackupFailsWithoutLeakingFailureDetails() throws Exception {
        AdminDataManagementService adminDataManagementService = mock(AdminDataManagementService.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        UserNotificationService userNotificationService = mock(UserNotificationService.class);
        DataOpsBackupScheduler scheduler = new DataOpsBackupScheduler(
                adminDataManagementService,
                appUserRepository,
                userNotificationService
        );
        setBooleanField(scheduler, "databaseBackupEnabled", true);
        doThrow(new IllegalStateException("rclone secret path /tmp/backup failed"))
                .when(adminDataManagementService)
                .createManualBackup();
        doThrow(new IllegalStateException("notification table unavailable"))
                .when(userNotificationService)
                .createSystemNotification(
                        eq(10L),
                        eq("BACKUP_FAILED"),
                        eq("Scheduled backup failed"),
                        contains("scheduled database backup failed"),
                        eq("/admin?panel=data-management"),
                        eq("{\"backupType\":\"database\",\"status\":\"failure\"}")
                );
        when(appUserRepository.findAllByRoleAndActiveTrueOrderByIdAsc(AppUserRole.ADMIN))
                .thenReturn(List.of(admin(10L), admin(11L)));

        scheduler.runScheduledDatabaseBackup();

        verify(userNotificationService).createSystemNotification(
                eq(10L),
                eq("BACKUP_FAILED"),
                eq("Scheduled backup failed"),
                contains("scheduled database backup failed"),
                eq("/admin?panel=data-management"),
                eq("{\"backupType\":\"database\",\"status\":\"failure\"}")
        );
        verify(userNotificationService).createSystemNotification(
                eq(11L),
                eq("BACKUP_FAILED"),
                eq("Scheduled backup failed"),
                contains("scheduled database backup failed"),
                eq("/admin?panel=data-management"),
                eq("{\"backupType\":\"database\",\"status\":\"failure\"}")
        );
    }

    private void setBooleanField(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private AppUser admin(Long id) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setRole(AppUserRole.ADMIN);
        user.setActive(true);
        return user;
    }
}