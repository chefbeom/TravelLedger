package com.playdata.calen.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.domain.SupportInquiryStatus;
import com.playdata.calen.account.dto.AdminBackupFileResponse;
import com.playdata.calen.account.dto.AdminDataManagementResponse;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminDataManagementService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BACKUP_FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private static final DateTimeFormatter DISPLAY_DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String DEFAULT_OPERATION_LABEL = "idle";

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

    @Value("${app.data-ops.rclone-config-path:/app/.config/rclone/rclone.conf}")
    private String rcloneConfigPath;

    private final ReentrantLock operationLock = new ReentrantLock();
    private final AtomicReference<String> runningOperation = new AtomicReference<>(DEFAULT_OPERATION_LABEL);

    public AdminDataManagementResponse getSnapshot() {
        List<AdminBackupFileResponse> backups = List.of();
        String backupsError = null;
        try {
            backups = listBackups();
        } catch (BadRequestException exception) {
            backupsError = exception.getMessage();
        }

        return new AdminDataManagementResponse(
                buildStats(),
                backups,
                backupsError,
                operationLock.isLocked(),
                currentOperation()
        );
    }

    public AdminBackupFileResponse createManualBackup() {
        return runExclusive("backup", () -> {
            DatabaseCommandTarget target = parseDataSourceUrl();
            Path backupDirectory = prepareOperationDirectory("backup");
            String fileName = "calen-" + LocalDateTime.now(KST).format(BACKUP_FILE_FORMATTER) + ".sql.gz";
            Path outputFile = backupDirectory.resolve(fileName);

            try {
                commandRunner.runDumpToGzip(buildDumpCommand(target), outputFile);
                long fileSize = fileSize(outputFile);
                uploadBackup(outputFile, fileName);
                return new AdminBackupFileResponse(
                        fileName,
                        fileSize,
                        DISPLAY_DATE_TIME_FORMATTER.format(LocalDateTime.now(KST))
                );
            } finally {
                cleanupPath(outputFile);
                cleanupPath(backupDirectory);
            }
        });
    }

    public void restoreBackup(String fileName) {
        validateBackupFileName(fileName);
        runExclusive("restore", () -> {
            DatabaseCommandTarget target = parseDataSourceUrl();
            Path restoreDirectory = prepareOperationDirectory("restore");
            Path downloadedFile = restoreDirectory.resolve(fileName);

            restoreMaintenanceService.start();
            try {
                downloadBackup(fileName, downloadedFile);
                clearCurrentDatabase();
                commandRunner.runGzipImport(downloadedFile, buildImportCommand(target));
            } finally {
                restoreMaintenanceService.finish();
                cleanupPath(downloadedFile);
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
                                item("삭제된 내역", formatCount(deletedLedgerEntries)),
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
                                item("여행 지출 합계(KRW)", formatAmount(totalTravelExpense))
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
                                item("답변 대기 문의", formatCount(supportInquiryRepository.countByStatusAndAdminDeletedFalse(SupportInquiryStatus.PENDING))),
                                item("답변 완료 문의", formatCount(supportInquiryRepository.countByStatusAndAdminDeletedFalse(SupportInquiryStatus.ANSWERED))),
                                item("보관 문의", formatCount(archivedSupportInquiries)),
                                item("초대 링크 총수", formatCount(accountInviteRepository.count())),
                                item("사용 가능한 초대", formatCount(accountInviteRepository.countByUsedAtIsNullAndExpiresAtAfter(LocalDateTime.now(KST))))
                        )
                )
        );

        return new AdminDataStatsResponse(sections);
    }

    private List<AdminBackupFileResponse> listBackups() {
        String resolvedRcloneConfig = resolveRcloneConfigPath();
        CommandResult result = commandRunner.run(List.of(
                "rclone",
                "--config",
                resolvedRcloneConfig,
                "lsjson",
                remoteDirectory(),
                "--files-only"
        ));

        if (result.exitCode() != 0) {
            String stderr = result.stderr() == null ? "" : result.stderr();
            if (stderr.toLowerCase().contains("directory not found")) {
                return List.of();
            }
            throw new BadRequestException(resolveOperationMessage("백업 목록을 불러오지 못했습니다.", result.stderr()));
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

    private void uploadBackup(Path localFile, String fileName) {
        String resolvedRcloneConfig = resolveRcloneConfigPath();
        CommandResult result = commandRunner.run(List.of(
                "rclone",
                "--config",
                resolvedRcloneConfig,
                "copyto",
                localFile.toString(),
                remoteDirectory() + "/" + fileName
        ));
        if (result.exitCode() != 0) {
            throw new BadRequestException(resolveOperationMessage("Google Drive 백업 업로드에 실패했습니다.", result.stderr()));
        }
    }

    private void downloadBackup(String fileName, Path localFile) {
        String resolvedRcloneConfig = resolveRcloneConfigPath();
        CommandResult result = commandRunner.run(List.of(
                "rclone",
                "--config",
                resolvedRcloneConfig,
                "copyto",
                remoteDirectory() + "/" + fileName,
                localFile.toString()
        ));
        if (result.exitCode() != 0) {
            throw new BadRequestException(resolveOperationMessage("선택한 백업 파일을 가져오지 못했습니다.", result.stderr()));
        }
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

    private record DatabaseCommandTarget(String host, int port, String database) {
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
