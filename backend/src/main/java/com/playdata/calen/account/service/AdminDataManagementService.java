package com.playdata.calen.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.domain.SupportInquiryStatus;
import com.playdata.calen.account.dto.AdminBackupFileResponse;
import com.playdata.calen.account.dto.AdminDataManagementResponse;
import com.playdata.calen.account.dto.AdminMinioStorageSummaryResponse;
import com.playdata.calen.account.dto.AdminDataStatItemResponse;
import com.playdata.calen.account.dto.AdminDataStatSectionResponse;
import com.playdata.calen.account.dto.AdminDataStatsResponse;
import com.playdata.calen.account.repository.AccountInviteRepository;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.LoginAuditLogRepository;
import com.playdata.calen.account.repository.SupportInquiryRepository;
import com.playdata.calen.common.exception.BadRequestException;
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
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class AdminDataManagementService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BACKUP_FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private static final DateTimeFormatter DISPLAY_DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String DEFAULT_OPERATION_LABEL = "idle";
    private static final String BACKUP_CACHE_FILE = "backup-manifest.json";
    private static final String LOCAL_BACKUP_DIRECTORY = "files";
    private static final String MINIO_BACKUP_CACHE_FILE = "minio-backup-manifest.json";
    private static final String LOCAL_MINIO_BACKUP_DIRECTORY = "minio-files";
    private static final long[] RCLONE_RETRY_DELAYS_MILLIS = {2_000L, 5_000L, 10_000L};

    private final AppUserRepository appUserRepository;
    private final AccountInviteRepository accountInviteRepository;
    private final LoginAuditLogRepository loginAuditLogRepository;
    private final LoginAttemptService loginAttemptService;
    private final SupportInquiryRepository supportInquiryRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final CategoryGroupRepository categoryGroupRepository;
    private final CategoryDetailRepository categoryDetailRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final TravelPlanRepository travelPlanRepository;
    private final TravelBudgetItemRepository travelBudgetItemRepository;
    private final TravelExpenseRecordRepository travelExpenseRecordRepository;
    private final TravelRouteSegmentRepository travelRouteSegmentRepository;
    private final TravelMediaAssetRepository travelMediaAssetRepository;
    private final TravelPlanShareRepository travelPlanShareRepository;
    private final FamilyCategoryRepository familyCategoryRepository;
    private final FamilyCategoryMemberRepository familyCategoryMemberRepository;
    private final FamilyAlbumRepository familyAlbumRepository;
    private final FamilyAlbumItemRepository familyAlbumItemRepository;
    private final FamilyMediaAssetRepository familyMediaAssetRepository;
    private final MinioBackupArchiveService minioBackupArchiveService;
    private final SystemCommandRunner commandRunner;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final RestoreMaintenanceService restoreMaintenanceService;

    @Value("${spring.datasource.url}")
    private String dataSourceUrl;

    @Value("${spring.datasource.username}")
    private String databaseUsername;

    @Value("${spring.datasource.password}")
    private String databasePassword;

    @Value("${app.data-ops.backup-workdir:/opt/calen-backup}")
    private String backupWorkdir;

    @Value("${app.data-ops.backup-remote-name:db-backup}")
    private String backupRemoteName;

    @Value("${app.data-ops.backup-remote-dir:calen-db-backups}")
    private String backupRemoteDir;

    @Value("${app.data-ops.minio-backup-remote-dir:calen-minio-backups}")
    private String minioBackupRemoteDir;

    @Value("${app.data-ops.rclone-config-path:/app/.config/rclone/rclone.conf}")
    private String rcloneConfigPath;

    private final ReentrantLock operationLock = new ReentrantLock();
    private final AtomicReference<String> runningOperation = new AtomicReference<>(DEFAULT_OPERATION_LABEL);

    public record PreparedBackupDownload(Path path, String fileName, long sizeBytes) {
    }

    public AdminDataManagementResponse getSnapshot() {
        List<AdminBackupFileResponse> backups = mergeBackupLists(loadCachedBackups(), listLocalBackups());
        String backupsError = null;
        List<AdminBackupFileResponse> minioBackups = mergeBackupLists(loadCachedMinioBackups(), listLocalMinioBackups());
        String minioBackupsError = null;
        AdminMinioStorageSummaryResponse minioStorage = minioBackupArchiveService.getSummary();

        try {
            backups = mergeBackupLists(listBackups(), listLocalBackups());
            persistBackupCache(backups);
        } catch (BadRequestException exception) {
            if (backups.isEmpty()) {
                backupsError = exception.getMessage();
            }
        }

        try {
            minioBackups = mergeBackupLists(listMinioBackups(), listLocalMinioBackups());
            persistMinioBackupCache(minioBackups);
        } catch (BadRequestException exception) {
            if (minioBackups.isEmpty()) {
                minioBackupsError = exception.getMessage();
            }
        }

        return new AdminDataManagementResponse(
                buildStats(),
                backups,
                backupsError,
                minioStorage,
                minioBackups,
                minioBackupsError,
                operationLock.isLocked(),
                currentOperation()
        );
    }

    public AdminBackupFileResponse createManualBackup() {
        return runExclusive("backup", () -> {
            DatabaseCommandTarget target = parseDataSourceUrl();
            Path outputFile = localBackupDirectory().resolve(
                    "calen-" + LocalDateTime.now(KST).format(BACKUP_FILE_FORMATTER) + ".sql.gz"
            );

            commandRunner.runDumpToGzip(buildDumpCommand(target), outputFile);
            long sizeBytes = fileSize(outputFile);

            try {
                uploadBackup(outputFile, outputFile.getFileName().toString());
            } catch (BadRequestException exception) {
                if (!isDriveQuotaFallbackMessage(exception.getMessage())) {
                    throw exception;
                }
            }

            AdminBackupFileResponse response = new AdminBackupFileResponse(
                    outputFile.getFileName().toString(),
                    sizeBytes,
                    DISPLAY_DATE_TIME_FORMATTER.format(LocalDateTime.now(KST))
            );
            updateBackupCache(response);
            return response;
        });
    }

    public AdminBackupFileResponse createManualMinioBackup() {
        return runExclusive("minio-backup", () -> {
            Path outputFile = localMinioBackupDirectory().resolve(
                    "calen-minio-" + LocalDateTime.now(KST).format(BACKUP_FILE_FORMATTER) + ".zip"
            );

            minioBackupArchiveService.writeBackupArchive(outputFile);
            long sizeBytes = fileSize(outputFile);

            try {
                uploadMinioBackup(outputFile, outputFile.getFileName().toString());
            } catch (BadRequestException exception) {
                if (!isDriveQuotaFallbackMessage(exception.getMessage())) {
                    throw exception;
                }
            }

            AdminBackupFileResponse response = new AdminBackupFileResponse(
                    outputFile.getFileName().toString(),
                    sizeBytes,
                    DISPLAY_DATE_TIME_FORMATTER.format(LocalDateTime.now(KST))
            );
            updateMinioBackupCache(response);
            return response;
        });
    }

    public PreparedBackupDownload createDownloadableBackup() {
        return runExclusive("backup", () -> {
            DatabaseCommandTarget target = parseDataSourceUrl();
            Path downloadDirectory = prepareOperationDirectory("backup-download");
            Path outputFile = downloadDirectory.resolve(
                    "calen-" + LocalDateTime.now(KST).format(BACKUP_FILE_FORMATTER) + ".sql.gz"
            );

            commandRunner.runDumpToGzip(buildDumpCommand(target), outputFile);
            return new PreparedBackupDownload(
                    outputFile,
                    outputFile.getFileName().toString(),
                    fileSize(outputFile)
            );
        });
    }

    public void restoreBackup(String fileName) {
        validateBackupFileName(fileName);
        runExclusive("restore", () -> {
            DatabaseCommandTarget target = parseDataSourceUrl();
            Path restoreDirectory = prepareOperationDirectory("restore");
            Path downloadedFile = restoreDirectory.resolve(fileName);
            Path localBackupFile = localBackupDirectory().resolve(fileName);
            Path restoreSource = Files.isReadable(localBackupFile) ? localBackupFile : downloadedFile;

            restoreMaintenanceService.start();
            try {
                if (!Files.isReadable(localBackupFile)) {
                    downloadBackup(fileName, downloadedFile);
                }
                clearCurrentDatabase();
                commandRunner.runGzipImport(restoreSource, buildImportCommand(target));
            } finally {
                restoreMaintenanceService.finish();
                cleanupPath(downloadedFile);
                cleanupPath(restoreDirectory);
            }
            return null;
        });
    }

    public void restoreUploadedBackup(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("복구할 SQL 파일을 선택해 주세요.");
        }

        String originalFileName = sanitizeUploadFileName(file.getOriginalFilename());
        if (!originalFileName.toLowerCase().endsWith(".sql")) {
            throw new BadRequestException("복구 업로드는 .sql 파일만 지원합니다.");
        }

        runExclusive("restore", () -> {
            DatabaseCommandTarget target = parseDataSourceUrl();
            Path restoreDirectory = prepareOperationDirectory("restore-upload");
            Path uploadedFile = restoreDirectory.resolve("uploaded-backup.sql");

            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, uploadedFile);
            } catch (IOException exception) {
                cleanupPath(uploadedFile);
                cleanupPath(restoreDirectory);
                throw new BadRequestException("업로드한 SQL 파일을 저장하지 못했습니다.");
            }

            restoreMaintenanceService.start();
            try {
                clearCurrentDatabase();
                commandRunner.runSqlImport(uploadedFile, buildImportCommand(target));
            } finally {
                restoreMaintenanceService.finish();
                cleanupPath(uploadedFile);
                cleanupPath(restoreDirectory);
            }
            return null;
        });
    }

    private AdminDataStatsResponse buildStats() {
        long totalUsers = appUserRepository.count();
        long activeUsers = appUserRepository.countByActiveTrue();
        long adminUsers = appUserRepository.countByRole(AppUserRole.ADMIN);
        long ledgerEntries = ledgerEntryRepository.countByDeletedAtIsNull();
        long deletedLedgerEntries = ledgerEntryRepository.countByDeletedAtIsNotNull();
        BigDecimal totalIncome = defaultAmount(ledgerEntryRepository.sumAmountByEntryTypeAndDeletedAtIsNull(EntryType.INCOME));
        BigDecimal totalExpense = defaultAmount(ledgerEntryRepository.sumAmountByEntryTypeAndDeletedAtIsNull(EntryType.EXPENSE));
        long travelPlans = travelPlanRepository.count();
        long completedTravelPlans = travelPlanRepository.countByStatus(TravelPlanStatus.COMPLETED);
        BigDecimal totalTravelExpense = defaultAmount(travelExpenseRecordRepository.sumAmountKrw());
        long totalSupportInquiries = supportInquiryRepository.countByAdminDeletedFalse();
        long archivedSupportInquiries = supportInquiryRepository.countByAdminArchivedTrueAndAdminDeletedFalse();

        List<AdminDataStatSectionResponse> sections = List.of(
                new AdminDataStatSectionResponse(
                        "accounts",
                        "계정 및 운영",
                        List.of(
                                item("전체 사용자", formatCount(totalUsers)),
                                item("활성 사용자", formatCount(activeUsers)),
                                item("관리자 계정", formatCount(adminUsers)),
                                item("로그인 기록", formatCount(loginAuditLogRepository.count())),
                                item("차단된 IP", formatCount(loginAttemptService.getBlockedIps().size()))
                        )
                ),
                new AdminDataStatSectionResponse(
                        "ledger",
                        "가계부 데이터",
                        List.of(
                                item("거래 내역", formatCount(ledgerEntries)),
                                item("휴지통 내역", formatCount(deletedLedgerEntries)),
                                item("총 수입", formatAmount(totalIncome)),
                                item("총 지출", formatAmount(totalExpense)),
                                item("분류 수", formatCount(categoryGroupRepository.count() + categoryDetailRepository.count())),
                                item("결제수단 수", formatCount(paymentMethodRepository.count()))
                        )
                ),
                new AdminDataStatSectionResponse(
                        "travel",
                        "여행 데이터",
                        List.of(
                                item("여행 계획", formatCount(travelPlans)),
                                item("완료 여행", formatCount(completedTravelPlans)),
                                item("예산 항목", formatCount(travelBudgetItemRepository.count())),
                                item("여행 기록", formatCount(travelExpenseRecordRepository.count())),
                                item("여행 사진", formatCount(travelMediaAssetRepository.count())),
                                item("이동 경로", formatCount(travelRouteSegmentRepository.count())),
                                item("공유 전시", formatCount(travelPlanShareRepository.count())),
                                item("여행 지출 합계", formatAmount(totalTravelExpense))
                        )
                ),
                new AdminDataStatSectionResponse(
                        "family",
                        "가족 앨범 데이터",
                        List.of(
                                item("가족 분류", formatCount(familyCategoryRepository.count())),
                                item("공유 구성원", formatCount(familyCategoryMemberRepository.count())),
                                item("앨범 수", formatCount(familyAlbumRepository.count())),
                                item("앨범 항목", formatCount(familyAlbumItemRepository.count())),
                                item("가족 사진", formatCount(familyMediaAssetRepository.count()))
                        )
                ),
                new AdminDataStatSectionResponse(
                        "support",
                        "문의 및 초대",
                        List.of(
                                item("문의 총수", formatCount(totalSupportInquiries)),
                                item("답변 대기", formatCount(supportInquiryRepository.countByStatusAndAdminDeletedFalse(SupportInquiryStatus.PENDING))),
                                item("답변 완료", formatCount(supportInquiryRepository.countByStatusAndAdminDeletedFalse(SupportInquiryStatus.ANSWERED))),
                                item("보관 문의", formatCount(archivedSupportInquiries)),
                                item("초대 링크 총수", formatCount(accountInviteRepository.count())),
                                item("사용 가능 초대", formatCount(accountInviteRepository.countByUsedAtIsNullAndExpiresAtAfter(LocalDateTime.now(KST))))
                        )
                )
        );

        return new AdminDataStatsResponse(sections);
    }

    private List<AdminBackupFileResponse> listBackups() {
        String resolvedRcloneConfig = resolveRcloneConfigPath();
        CommandResult result = runRcloneListCommand(List.of(
                "rclone",
                "--config",
                resolvedRcloneConfig,
                "lsjson",
                remoteDirectory(),
                "--files-only"
        ));

        String stderr = result.stderr() == null ? "" : result.stderr();
        if (stderr.toLowerCase().contains("directory not found")) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(result.stdout());
            List<AdminBackupFileResponse> backups = new ArrayList<>();
            for (JsonNode node : root) {
                backups.add(new AdminBackupFileResponse(
                        node.path("Name").asText(),
                        node.path("Size").asLong(),
                        node.path("ModTime").asText()
                ));
            }
            backups.sort(Comparator.comparing(AdminBackupFileResponse::modifiedAt, Comparator.nullsLast(Comparator.reverseOrder())));
            return backups;
        } catch (Exception exception) {
            throw new BadRequestException("백업 목록 응답을 해석하지 못했습니다.");
        }
    }

    private List<AdminBackupFileResponse> listMinioBackups() {
        String resolvedRcloneConfig = resolveRcloneConfigPath();
        CommandResult result = runRcloneListCommand(List.of(
                "rclone",
                "--config",
                resolvedRcloneConfig,
                "lsjson",
                minioRemoteDirectory(),
                "--files-only"
        ));

        String stderr = result.stderr() == null ? "" : result.stderr();
        if (stderr.toLowerCase().contains("directory not found")) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(result.stdout());
            List<AdminBackupFileResponse> backups = new ArrayList<>();
            for (JsonNode node : root) {
                backups.add(new AdminBackupFileResponse(
                        node.path("Name").asText(),
                        node.path("Size").asLong(),
                        node.path("ModTime").asText()
                ));
            }
            backups.sort(Comparator.comparing(AdminBackupFileResponse::modifiedAt, Comparator.nullsLast(Comparator.reverseOrder())));
            return backups;
        } catch (Exception exception) {
            throw new BadRequestException("MinIO 백업 목록 응답을 해석하지 못했습니다.");
        }
    }

    private List<AdminBackupFileResponse> listLocalBackups() {
        Path directory = localBackupDirectory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("[A-Za-z0-9._-]+\\.sql\\.gz"))
                    .map(this::toLocalBackupResponse)
                    .sorted(Comparator.comparing(AdminBackupFileResponse::modifiedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private List<AdminBackupFileResponse> listLocalMinioBackups() {
        Path directory = localMinioBackupDirectory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("[A-Za-z0-9._-]+\\.zip"))
                    .map(this::toLocalBackupResponse)
                    .sorted(Comparator.comparing(AdminBackupFileResponse::modifiedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private AdminBackupFileResponse toLocalBackupResponse(Path path) {
        try {
            FileTime modifiedTime = Files.getLastModifiedTime(path);
            return new AdminBackupFileResponse(
                    path.getFileName().toString(),
                    fileSize(path),
                    DISPLAY_DATE_TIME_FORMATTER.format(LocalDateTime.ofInstant(modifiedTime.toInstant(), KST))
            );
        } catch (IOException exception) {
            return new AdminBackupFileResponse(
                    path.getFileName().toString(),
                    fileSize(path),
                    DISPLAY_DATE_TIME_FORMATTER.format(LocalDateTime.now(KST))
            );
        }
    }

    private List<AdminBackupFileResponse> mergeBackupLists(List<AdminBackupFileResponse> primary, List<AdminBackupFileResponse> secondary) {
        Map<String, AdminBackupFileResponse> merged = new LinkedHashMap<>();
        for (AdminBackupFileResponse backup : primary) {
            merged.put(backup.fileName(), backup);
        }
        for (AdminBackupFileResponse backup : secondary) {
            merged.putIfAbsent(backup.fileName(), backup);
        }

        return merged.values().stream()
                .sorted(Comparator.comparing(AdminBackupFileResponse::modifiedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private void uploadBackup(Path localFile, String fileName) {
        String resolvedRcloneConfig = resolveRcloneConfigPath();
        runRcloneCommand(List.of(
                "rclone",
                "--config",
                resolvedRcloneConfig,
                "copyto",
                localFile.toString(),
                remoteDirectory() + "/" + fileName
        ), "Google Drive 백업 업로드에 실패했습니다.");
    }

    private void uploadMinioBackup(Path localFile, String fileName) {
        String resolvedRcloneConfig = resolveRcloneConfigPath();
        runRcloneCommand(List.of(
                "rclone",
                "--config",
                resolvedRcloneConfig,
                "copyto",
                localFile.toString(),
                minioRemoteDirectory() + "/" + fileName
        ), "Google Drive MinIO 백업 업로드에 실패했습니다.");
    }

    private void downloadBackup(String fileName, Path localFile) {
        String resolvedRcloneConfig = resolveRcloneConfigPath();
        runRcloneCommand(List.of(
                "rclone",
                "--config",
                resolvedRcloneConfig,
                "copyto",
                remoteDirectory() + "/" + fileName,
                localFile.toString()
        ), "선택한 백업 파일을 가져오지 못했습니다.");
    }

    private List<String> buildDumpCommand(DatabaseCommandTarget target) {
        return List.of(
                "mariadb-dump",
                "--host=" + target.host(),
                "--port=" + target.port(),
                "--user=" + databaseUsername,
                "--password=" + databasePassword,
                "--default-character-set=utf8mb4",
                target.database()
        );
    }

    private List<String> buildImportCommand(DatabaseCommandTarget target) {
        return List.of(
                "mariadb",
                "--host=" + target.host(),
                "--port=" + target.port(),
                "--user=" + databaseUsername,
                "--password=" + databasePassword,
                "--default-character-set=utf8mb4",
                target.database()
        );
    }

    private DatabaseCommandTarget parseDataSourceUrl() {
        String prefix = "jdbc:mariadb://";
        if (!dataSourceUrl.startsWith(prefix)) {
            throw new BadRequestException("데이터베이스 연결 정보를 백업 형식으로 해석할 수 없습니다.");
        }

        String withoutPrefix = dataSourceUrl.substring(prefix.length());
        int slashIndex = withoutPrefix.indexOf('/');
        if (slashIndex < 0) {
            throw new BadRequestException("데이터베이스 연결 정보가 올바르지 않습니다.");
        }

        String hostPort = withoutPrefix.substring(0, slashIndex);
        String databaseAndQuery = withoutPrefix.substring(slashIndex + 1);
        int queryIndex = databaseAndQuery.indexOf('?');
        String database = queryIndex >= 0 ? databaseAndQuery.substring(0, queryIndex) : databaseAndQuery;
        String[] parts = hostPort.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 3306;

        return new DatabaseCommandTarget(host, port, database);
    }

    private Path workdir() {
        Path configuredPath = Path.of(backupWorkdir);
        if (canUseDirectory(configuredPath)) {
            return configuredPath;
        }

        Path fallbackPath = Path.of(System.getProperty("java.io.tmpdir"), "calen-data-ops");
        if (canUseDirectory(fallbackPath)) {
            return fallbackPath;
        }

        throw new BadRequestException("데이터 작업용 폴더를 준비하지 못했습니다.");
    }

    private Path localBackupDirectory() {
        Path directory = workdir().resolve(LOCAL_BACKUP_DIRECTORY);
        if (canUseDirectory(directory)) {
            return directory;
        }
        throw new BadRequestException("로컬 백업 폴더를 준비하지 못했습니다.");
    }

    private Path localMinioBackupDirectory() {
        Path directory = workdir().resolve(LOCAL_MINIO_BACKUP_DIRECTORY);
        if (canUseDirectory(directory)) {
            return directory;
        }
        throw new BadRequestException("로컬 MinIO 백업 폴더를 준비하지 못했습니다.");
    }

    private boolean canUseDirectory(Path path) {
        try {
            Files.createDirectories(path);
            Path probe = Files.createTempFile(path, "probe-", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private Path prepareOperationDirectory(String prefix) {
        try {
            return Files.createTempDirectory(workdir(), prefix + "-");
        } catch (Exception exception) {
            throw new BadRequestException("데이터 작업용 폴더를 준비하지 못했습니다.");
        }
    }

    private void cleanupPath(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Best-effort cleanup only.
        }
    }

    private CommandResult runRcloneCommand(List<String> command, String fallbackMessage) {
        CommandResult lastResult = null;

        for (int attempt = 0; attempt <= RCLONE_RETRY_DELAYS_MILLIS.length; attempt += 1) {
            lastResult = commandRunner.run(command);
            if (lastResult.exitCode() == 0) {
                return lastResult;
            }

            if (!isDriveRateLimited(lastResult.stderr()) || attempt == RCLONE_RETRY_DELAYS_MILLIS.length) {
                break;
            }

            sleepRetryDelay(RCLONE_RETRY_DELAYS_MILLIS[attempt]);
        }

        String stderr = lastResult == null ? "" : lastResult.stderr();
        if (isDriveRateLimited(stderr)) {
            throw new BadRequestException("Google Drive API 요청 한도를 초과했습니다. 잠시 후 다시 시도하거나, rclone에 개인 Google API client_id/client_secret을 설정해 주세요.");
        }

        throw new BadRequestException(resolveOperationMessage(fallbackMessage, stderr));
    }

    private CommandResult runRcloneListCommand(List<String> command) {
        CommandResult lastResult = null;

        for (int attempt = 0; attempt <= RCLONE_RETRY_DELAYS_MILLIS.length; attempt += 1) {
            lastResult = commandRunner.run(command);
            if (lastResult.exitCode() == 0) {
                return lastResult;
            }

            String stderr = lastResult.stderr() == null ? "" : lastResult.stderr().toLowerCase();
            if (stderr.contains("directory not found")) {
                return lastResult;
            }

            if (!isDriveRateLimited(lastResult.stderr()) || attempt == RCLONE_RETRY_DELAYS_MILLIS.length) {
                break;
            }

            sleepRetryDelay(RCLONE_RETRY_DELAYS_MILLIS[attempt]);
        }

        String stderr = lastResult == null ? "" : lastResult.stderr();
        if (isDriveRateLimited(stderr)) {
            throw new BadRequestException("Google Drive API 요청 한도를 초과했습니다. 잠시 후 다시 시도하거나, rclone에 개인 Google API client_id/client_secret을 설정해 주세요.");
        }

        throw new BadRequestException(resolveOperationMessage("백업 목록을 불러오지 못했습니다.", stderr));
    }

    private boolean isDriveRateLimited(String stderr) {
        String normalized = stderr == null ? "" : stderr.toLowerCase();
        return normalized.contains("ratelimitexceeded")
                || normalized.contains("rate_limit_exceeded")
                || normalized.contains("quota exceeded")
                || normalized.contains("quota metric")
                || normalized.contains("couldn't find root directory id");
    }

    private boolean isDriveQuotaFallbackMessage(String message) {
        String normalized = message == null ? "" : message.toLowerCase();
        return normalized.contains("google drive api")
                || normalized.contains("quota")
                || normalized.contains("rate");
    }

    private void sleepRetryDelay(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("데이터 작업이 중단되었습니다.");
        }
    }

    private String resolveRcloneConfigPath() {
        List<Path> candidates = List.of(
                Path.of(rcloneConfigPath),
                Path.of("/app/.config/rclone/rclone.conf"),
                Path.of(System.getProperty("user.home"), ".config", "rclone", "rclone.conf"),
                Path.of("/root/.config/rclone/rclone.conf"),
                Path.of("/home/ubuntu/.config/rclone/rclone.conf"),
                Path.of("/home/opc/.config/rclone/rclone.conf")
        );

        for (Path candidate : candidates) {
            if (Files.isReadable(candidate)) {
                return candidate.toString();
            }
        }

        throw new BadRequestException("rclone 설정 파일을 찾을 수 없습니다. 서버 백업 설정을 먼저 확인해 주세요.");
    }

    private List<AdminBackupFileResponse> loadCachedBackups() {
        Path cacheFile = backupCacheFile();
        if (!Files.isReadable(cacheFile)) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(cacheFile.toFile());
            List<AdminBackupFileResponse> cachedBackups = new ArrayList<>();
            for (JsonNode node : root) {
                cachedBackups.add(new AdminBackupFileResponse(
                        node.path("fileName").asText(),
                        node.path("sizeBytes").asLong(),
                        node.path("modifiedAt").asText()
                ));
            }
            cachedBackups.sort(Comparator.comparing(AdminBackupFileResponse::modifiedAt, Comparator.nullsLast(Comparator.reverseOrder())));
            return cachedBackups;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<AdminBackupFileResponse> loadCachedMinioBackups() {
        Path cacheFile = minioBackupCacheFile();
        if (!Files.isReadable(cacheFile)) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(cacheFile.toFile());
            List<AdminBackupFileResponse> cachedBackups = new ArrayList<>();
            for (JsonNode node : root) {
                cachedBackups.add(new AdminBackupFileResponse(
                        node.path("fileName").asText(),
                        node.path("sizeBytes").asLong(),
                        node.path("modifiedAt").asText()
                ));
            }
            cachedBackups.sort(Comparator.comparing(AdminBackupFileResponse::modifiedAt, Comparator.nullsLast(Comparator.reverseOrder())));
            return cachedBackups;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void persistBackupCache(List<AdminBackupFileResponse> backups) {
        Path cacheFile = backupCacheFile();
        try {
            Files.createDirectories(cacheFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), backups);
        } catch (Exception ignored) {
            // Cache write failure should not break backup UI.
        }
    }

    private void persistMinioBackupCache(List<AdminBackupFileResponse> backups) {
        Path cacheFile = minioBackupCacheFile();
        try {
            Files.createDirectories(cacheFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), backups);
        } catch (Exception ignored) {
            // Cache write failure should not break backup UI.
        }
    }

    private void updateBackupCache(AdminBackupFileResponse backup) {
        List<AdminBackupFileResponse> cachedBackups = new ArrayList<>(mergeBackupLists(loadCachedBackups(), listLocalBackups()));
        cachedBackups.removeIf(item -> item.fileName().equals(backup.fileName()));
        cachedBackups.add(backup);
        cachedBackups.sort(Comparator.comparing(AdminBackupFileResponse::modifiedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        persistBackupCache(cachedBackups);
    }

    private void updateMinioBackupCache(AdminBackupFileResponse backup) {
        List<AdminBackupFileResponse> cachedBackups = new ArrayList<>(mergeBackupLists(loadCachedMinioBackups(), listLocalMinioBackups()));
        cachedBackups.removeIf(item -> item.fileName().equals(backup.fileName()));
        cachedBackups.add(backup);
        cachedBackups.sort(Comparator.comparing(AdminBackupFileResponse::modifiedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        persistMinioBackupCache(cachedBackups);
    }

    private Path backupCacheFile() {
        return workdir().resolve(BACKUP_CACHE_FILE);
    }

    private Path minioBackupCacheFile() {
        return workdir().resolve(MINIO_BACKUP_CACHE_FILE);
    }

    private void clearCurrentDatabase() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            dropObjects("VIEW");
            dropObjects("BASE TABLE");
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    private void dropObjects(String tableType) {
        List<String> objectNames = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = ?",
                String.class,
                tableType
        );

        String keyword = "VIEW".equals(tableType) ? "VIEW" : "TABLE";
        for (String objectName : objectNames) {
            jdbcTemplate.execute("DROP " + keyword + " IF EXISTS `" + escapeIdentifier(objectName) + "`");
        }
    }

    private String escapeIdentifier(String value) {
        return value.replace("`", "``");
    }

    private void validateBackupFileName(String fileName) {
        if (fileName == null || !fileName.matches("[A-Za-z0-9._-]+\\.sql\\.gz")) {
            throw new BadRequestException("복구할 백업 파일 이름이 올바르지 않습니다.");
        }
    }

    private String remoteDirectory() {
        return backupRemoteName + ":" + backupRemoteDir;
    }

    private String minioRemoteDirectory() {
        return backupRemoteName + ":" + minioBackupRemoteDir;
    }

    private long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (Exception exception) {
            return 0L;
        }
    }

    private <T> T runExclusive(String operation, ThrowingSupplier<T> action) {
        if (!operationLock.tryLock()) {
            throw new BadRequestException("다른 데이터 작업이 진행 중입니다. 잠시 후 다시 시도해 주세요.");
        }

        runningOperation.set(operation);
        try {
            return action.get();
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadRequestException("데이터 작업을 처리하지 못했습니다.");
        } finally {
            runningOperation.set(DEFAULT_OPERATION_LABEL);
            operationLock.unlock();
        }
    }

    private String currentOperation() {
        String value = runningOperation.get();
        return value == null || value.isBlank() ? DEFAULT_OPERATION_LABEL : value;
    }

    private BigDecimal defaultAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private AdminDataStatItemResponse item(String label, String value) {
        return new AdminDataStatItemResponse(label, value);
    }

    private String formatCount(long value) {
        return "%,d개".formatted(value);
    }

    private String formatAmount(BigDecimal value) {
        return "₩%,.0f".formatted(value.setScale(0, RoundingMode.HALF_UP).doubleValue());
    }

    private String resolveOperationMessage(String fallback, String stderr) {
        String normalized = stderr == null ? "" : stderr.trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    public void cleanupPreparedBackup(PreparedBackupDownload preparedBackupDownload) {
        if (preparedBackupDownload == null) {
            return;
        }
        cleanupPath(preparedBackupDownload.path());
        cleanupPath(preparedBackupDownload.path().getParent());
    }

    private String sanitizeUploadFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "uploaded-backup.sql";
        }
        return Path.of(fileName).getFileName().toString();
    }

    private record DatabaseCommandTarget(String host, int port, String database) {
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
