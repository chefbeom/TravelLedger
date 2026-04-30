package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.domain.SupportInquiryStatus;
import com.playdata.calen.account.dto.AdminBackupFileResponse;
import com.playdata.calen.account.dto.AdminDataManagementResponse;
import com.playdata.calen.account.dto.AdminMinioStorageSummaryResponse;
import com.playdata.calen.account.service.AdminDataManagementService;
import com.playdata.calen.account.service.CommandResult;
import com.playdata.calen.account.service.LoginAttemptService;
import com.playdata.calen.account.service.MinioBackupArchiveService;
import com.playdata.calen.account.service.RestoreMaintenanceService;
import com.playdata.calen.account.service.SystemCommandRunner;
import com.playdata.calen.account.repository.AccountInviteRepository;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.LoginAuditLogRepository;
import com.playdata.calen.account.repository.SupportInquiryRepository;
import com.playdata.calen.familyalbum.repository.FamilyAlbumItemRepository;
import com.playdata.calen.familyalbum.repository.FamilyAlbumRepository;
import com.playdata.calen.familyalbum.repository.FamilyCategoryMemberRepository;
import com.playdata.calen.familyalbum.repository.FamilyCategoryRepository;
import com.playdata.calen.familyalbum.repository.FamilyMediaAssetRepository;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.repository.TravelBudgetItemRepository;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import com.playdata.calen.travel.repository.TravelPlanShareRepository;
import com.playdata.calen.travel.repository.TravelRouteSegmentRepository;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminDataManagementServiceTest {

    @Mock private AppUserRepository appUserRepository;
    @Mock private AccountInviteRepository accountInviteRepository;
    @Mock private LoginAuditLogRepository loginAuditLogRepository;
    @Mock private LoginAttemptService loginAttemptService;
    @Mock private SupportInquiryRepository supportInquiryRepository;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private CategoryGroupRepository categoryGroupRepository;
    @Mock private CategoryDetailRepository categoryDetailRepository;
    @Mock private PaymentMethodRepository paymentMethodRepository;
    @Mock private TravelPlanRepository travelPlanRepository;
    @Mock private TravelBudgetItemRepository travelBudgetItemRepository;
    @Mock private TravelExpenseRecordRepository travelExpenseRecordRepository;
    @Mock private TravelRouteSegmentRepository travelRouteSegmentRepository;
    @Mock private TravelMediaAssetRepository travelMediaAssetRepository;
    @Mock private TravelPlanShareRepository travelPlanShareRepository;
    @Mock private FamilyCategoryRepository familyCategoryRepository;
    @Mock private FamilyCategoryMemberRepository familyCategoryMemberRepository;
    @Mock private FamilyAlbumRepository familyAlbumRepository;
    @Mock private FamilyAlbumItemRepository familyAlbumItemRepository;
    @Mock private FamilyMediaAssetRepository familyMediaAssetRepository;
    @Mock private MinioBackupArchiveService minioBackupArchiveService;
    @Mock private SystemCommandRunner commandRunner;
    @Mock private JdbcTemplate jdbcTemplate;

    private AdminDataManagementService service;
    private Path tempDir;
    private Path rcloneConfig;

    @BeforeEach
    void setUp() throws Exception {
        service = new AdminDataManagementService(
                appUserRepository,
                accountInviteRepository,
                loginAuditLogRepository,
                loginAttemptService,
                supportInquiryRepository,
                ledgerEntryRepository,
                categoryGroupRepository,
                categoryDetailRepository,
                paymentMethodRepository,
                travelPlanRepository,
                travelBudgetItemRepository,
                travelExpenseRecordRepository,
                travelRouteSegmentRepository,
                travelMediaAssetRepository,
                travelPlanShareRepository,
                familyCategoryRepository,
                familyCategoryMemberRepository,
                familyAlbumRepository,
                familyAlbumItemRepository,
                familyMediaAssetRepository,
                minioBackupArchiveService,
                commandRunner,
                new ObjectMapper(),
                jdbcTemplate,
                new RestoreMaintenanceService()
        );

        tempDir = Files.createTempDirectory("admin-data-management-test");
        Path configDir = Files.createDirectories(tempDir.resolve("config"));
        rcloneConfig = Files.writeString(configDir.resolve("rclone.conf"), "[db-backup]\ntype=drive\n");

        ReflectionTestUtils.setField(service, "dataSourceUrl", "jdbc:mariadb://mariadb:3306/calen?useSSL=false");
        ReflectionTestUtils.setField(service, "databaseUsername", "calen");
        ReflectionTestUtils.setField(service, "databasePassword", "secret");
        ReflectionTestUtils.setField(service, "backupWorkdir", tempDir.toString());
        ReflectionTestUtils.setField(service, "backupRemoteName", "db-backup");
        ReflectionTestUtils.setField(service, "backupRemoteDir", "calen-db-backups");
        ReflectionTestUtils.setField(service, "minioBackupRemoteDir", "calen-minio-backups");
        ReflectionTestUtils.setField(service, "rcloneConfigPath", rcloneConfig.toString());

        when(appUserRepository.count()).thenReturn(3L);
        when(appUserRepository.countByActiveTrue()).thenReturn(2L);
        when(appUserRepository.countByRole(AppUserRole.ADMIN)).thenReturn(1L);
        when(loginAuditLogRepository.count()).thenReturn(12L);
        when(loginAttemptService.getBlockedIps()).thenReturn(List.of(
                new LoginAttemptService.BlockedIpSnapshot("127.0.0.1", 5, Instant.now().plusSeconds(86_400))
        ));
        when(ledgerEntryRepository.countByDeletedAtIsNull()).thenReturn(20L);
        when(ledgerEntryRepository.countByDeletedAtIsNotNull()).thenReturn(2L);
        when(ledgerEntryRepository.sumAmountByEntryTypeAndDeletedAtIsNull(EntryType.INCOME)).thenReturn(new BigDecimal("100000"));
        when(ledgerEntryRepository.sumAmountByEntryTypeAndDeletedAtIsNull(EntryType.EXPENSE)).thenReturn(new BigDecimal("45000"));
        when(categoryGroupRepository.count()).thenReturn(6L);
        when(categoryDetailRepository.count()).thenReturn(14L);
        when(paymentMethodRepository.count()).thenReturn(5L);
        when(travelPlanRepository.count()).thenReturn(4L);
        when(travelPlanRepository.countByStatus(TravelPlanStatus.COMPLETED)).thenReturn(2L);
        when(travelBudgetItemRepository.count()).thenReturn(11L);
        when(travelExpenseRecordRepository.count()).thenReturn(17L);
        when(travelExpenseRecordRepository.sumAmountKrw()).thenReturn(new BigDecimal("880000"));
        when(travelRouteSegmentRepository.count()).thenReturn(3L);
        when(travelMediaAssetRepository.count()).thenReturn(8L);
        when(travelPlanShareRepository.count()).thenReturn(2L);
        when(familyCategoryRepository.count()).thenReturn(4L);
        when(familyCategoryMemberRepository.count()).thenReturn(7L);
        when(familyAlbumRepository.count()).thenReturn(5L);
        when(familyAlbumItemRepository.count()).thenReturn(9L);
        when(familyMediaAssetRepository.count()).thenReturn(13L);
        when(supportInquiryRepository.countByAdminDeletedFalse()).thenReturn(6L);
        when(supportInquiryRepository.countByStatusAndAdminDeletedFalse(SupportInquiryStatus.PENDING)).thenReturn(2L);
        when(supportInquiryRepository.countByStatusAndAdminDeletedFalse(SupportInquiryStatus.ANSWERED)).thenReturn(4L);
        when(supportInquiryRepository.countByAdminArchivedTrueAndAdminDeletedFalse()).thenReturn(3L);
        when(accountInviteRepository.count()).thenReturn(5L);
        when(accountInviteRepository.countByUsedAtIsNullAndExpiresAtAfter(any())).thenReturn(2L);
        when(minioBackupArchiveService.getSummary()).thenReturn(
                new AdminMinioStorageSummaryResponse(true, "budgetjourneybucket", 12L, 4096L, null)
        );
    }

    @Test
    void getSnapshotReturnsStatsAndBackupList() {
        when(commandRunner.run(any())).thenReturn(new CommandResult(
                0,
                """
                [
                  {
                    "Name": "calen-2026-03-31-120000.sql.gz",
                    "Size": 2048,
                    "ModTime": "2026-03-31T12:00:00+09:00"
                  }
                ]
                """,
                ""
        ));

        AdminDataManagementResponse response = service.getSnapshot();

        assertThat(response.stats().sections()).hasSize(5);
        assertThat(response.backups()).hasSize(1);
        assertThat(response.backups().get(0).fileName()).isEqualTo("calen-2026-03-31-120000.sql.gz");
        assertThat(response.backupsError()).isNull();
        assertThat(response.minioStorage().bucketName()).isEqualTo("budgetjourneybucket");
    }

    @Test
    void getSnapshotFallsBackToLocalBackupsWhenDriveIsRateLimited() throws Exception {
        Path localBackup = Files.createDirectories(tempDir.resolve("files"))
                .resolve("calen-2026-03-31-130000.sql.gz");
        Files.writeString(localBackup, "backup");

        when(commandRunner.run(any())).thenReturn(new CommandResult(
                1,
                "",
                "googleapi: Error 403: RATE_LIMIT_EXCEEDED"
        ));

        AdminDataManagementResponse response = service.getSnapshot();

        assertThat(response.backups()).extracting(AdminBackupFileResponse::fileName)
                .contains("calen-2026-03-31-130000.sql.gz");
        assertThat(response.backupsError()).isNull();
    }

    @Test
    void createManualBackupKeepsLocalBackupWhenDriveIsRateLimited() throws Exception {
        doAnswer(invocation -> {
            Path outputFile = invocation.getArgument(1, Path.class);
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, "backup");
            return null;
        }).when(commandRunner).runDumpToGzip(any(), any());

        when(commandRunner.run(any())).thenReturn(new CommandResult(
                1,
                "",
                "googleapi: Error 403: RATE_LIMIT_EXCEEDED"
        ));

        AdminBackupFileResponse response = service.createManualBackup();

        Path createdBackup = tempDir.resolve("files").resolve(response.fileName());
        assertThat(Files.exists(createdBackup)).isTrue();
        assertThat(response.fileName()).endsWith(".sql.gz");
    }

    @Test
    void createManualBackupDeletesLocalBackupAfterSuccessfulUpload() throws Exception {
        doAnswer(invocation -> {
            Path outputFile = invocation.getArgument(1, Path.class);
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, "backup");
            return null;
        }).when(commandRunner).runDumpToGzip(any(), any());

        when(commandRunner.run(any())).thenReturn(new CommandResult(0, "", ""));

        AdminBackupFileResponse response = service.createManualBackup();

        Path createdBackup = tempDir.resolve("files").resolve(response.fileName());
        assertThat(Files.exists(createdBackup)).isFalse();
        assertThat(response.fileName()).endsWith(".sql.gz");
    }

    @Test
    void createBackupAndRestoreUseExpectedCommands() {
        when(commandRunner.run(any()))
                .thenReturn(new CommandResult(0, "", ""))
                .thenReturn(new CommandResult(0, "", ""));
        when(jdbcTemplate.queryForList(any(String.class), org.mockito.ArgumentMatchers.eq(String.class), any()))
                .thenReturn(List.of())
                .thenReturn(List.of());

        service.createManualBackup();
        service.restoreBackup("calen-2026-03-31-120000.sql.gz");

        verify(commandRunner).runDumpToGzip(any(), any());
        verify(commandRunner).run(argThat(command ->
                command.size() == 6
                        && "rclone".equals(command.get(0))
                        && "--config".equals(command.get(1))
                        && rcloneConfig.toString().equals(command.get(2))
                        && "copyto".equals(command.get(3))
                        && command.get(4).contains(tempDir.toString())
                        && command.get(4).endsWith(".sql.gz")
                        && command.get(5).startsWith("db-backup:calen-db-backups/calen-")
                        && command.get(5).endsWith(".sql.gz")
        ));
        verify(commandRunner).run(argThat(command ->
                command.size() == 6
                        && "rclone".equals(command.get(0))
                        && "--config".equals(command.get(1))
                        && rcloneConfig.toString().equals(command.get(2))
                        && "copyto".equals(command.get(3))
                        && "db-backup:calen-db-backups/calen-2026-03-31-120000.sql.gz".equals(command.get(4))
                        && command.get(5).contains(tempDir.toString())
                        && command.get(5).endsWith("calen-2026-03-31-120000.sql.gz")
        ));
        verify(commandRunner).runGzipImport(argThat(path ->
                path.toString().contains(tempDir.toString())
                        && path.toString().endsWith("calen-2026-03-31-120000.sql.gz")
        ), any());
    }

    @Test
    void createManualMinioBackupUsesArchiveServiceAndUploadsToDedicatedDirectory() throws Exception {
        doAnswer(invocation -> {
            Path outputFile = invocation.getArgument(0, Path.class);
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, "minio-backup");
            return null;
        }).when(minioBackupArchiveService).writeBackupArchive(any());

        when(commandRunner.run(any())).thenReturn(new CommandResult(0, "", ""));

        AdminBackupFileResponse response = service.createManualMinioBackup();

        assertThat(response.fileName()).startsWith("calen-minio-");
        assertThat(response.fileName()).endsWith(".zip");
        assertThat(Files.exists(tempDir.resolve("minio-files").resolve(response.fileName()))).isFalse();
        verify(minioBackupArchiveService).writeBackupArchive(argThat(path ->
                path.toString().contains("minio-files")
                        && path.toString().endsWith(".zip")
        ));
        verify(commandRunner).run(argThat(command ->
                command.size() == 6
                        && "rclone".equals(command.get(0))
                        && "--config".equals(command.get(1))
                        && rcloneConfig.toString().equals(command.get(2))
                        && "copyto".equals(command.get(3))
                        && command.get(4).contains("minio-files")
                        && command.get(4).endsWith(".zip")
                        && command.get(5).startsWith("db-backup:calen-minio-backups/calen-minio-")
                        && command.get(5).endsWith(".zip")
        ));
    }

    @Test
    void createDownloadableBackupProducesSqlGzipFileWithoutRcloneUpload() {
        doAnswer(invocation -> {
            Path outputFile = invocation.getArgument(1, Path.class);
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, "backup");
            return null;
        }).when(commandRunner).runDumpToGzip(any(), any());

        AdminDataManagementService.PreparedBackupDownload preparedBackup = service.createDownloadableBackup();

        assertThat(preparedBackup.fileName()).endsWith(".sql.gz");
        assertThat(Files.exists(preparedBackup.path())).isTrue();
        verify(commandRunner).runDumpToGzip(any(), any());
    }

    @Test
    void restoreUploadedBackupUsesPlainSqlImport() {
        when(jdbcTemplate.queryForList(any(String.class), org.mockito.ArgumentMatchers.eq(String.class), any()))
                .thenReturn(List.of())
                .thenReturn(List.of());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "restore.sql",
                "application/sql",
                "select 1;".getBytes()
        );

        service.restoreUploadedBackup(file);

        verify(commandRunner).runSqlImport(argThat(path ->
                path.toString().contains("restore-upload")
                        && path.toString().endsWith("uploaded-backup.sql")
        ), any());
    }
}
