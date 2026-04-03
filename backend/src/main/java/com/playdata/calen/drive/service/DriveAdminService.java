package com.playdata.calen.drive.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.drive.domain.DriveAdminConfig;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveAdminConfigRepository;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.drive.repository.DriveShareRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DriveAdminService {

    private final AppUserRepository appUserRepository;
    private final DriveItemRepository driveItemRepository;
    private final DriveShareRepository driveShareRepository;
    private final DriveAdminConfigRepository driveAdminConfigRepository;
    private final DriveStorageService driveStorageService;

    public DriveAdminService(
            AppUserRepository appUserRepository,
            DriveItemRepository driveItemRepository,
            DriveShareRepository driveShareRepository,
            DriveAdminConfigRepository driveAdminConfigRepository,
            DriveStorageService driveStorageService
    ) {
        this.appUserRepository = appUserRepository;
        this.driveItemRepository = driveItemRepository;
        this.driveShareRepository = driveShareRepository;
        this.driveAdminConfigRepository = driveAdminConfigRepository;
        this.driveStorageService = driveStorageService;
    }

    public DriveDtos.AdminDashboardResponse getDashboard(boolean admin) {
        ensureAdmin(admin);
        List<AppUser> users = appUserRepository.findAllByOrderByIdAsc();
        List<DriveItem> items = driveItemRepository.findAll();
        Map<Long, List<DriveItem>> itemsByUser = items.stream()
                .collect(Collectors.groupingBy(item -> item.getOwner().getId()));

        List<DriveDtos.AdminUserResponse> userResponses = users.stream()
                .map(user -> toAdminUserResponse(user, itemsByUser.getOrDefault(user.getId(), List.of())))
                .sorted(Comparator.comparing(DriveDtos.AdminUserResponse::usedBytes).reversed())
                .toList();

        return DriveDtos.AdminDashboardResponse.builder()
                .summary(DriveDtos.AdminSummaryResponse.builder()
                        .totalUserCount(users.size())
                        .activeUserCount(users.stream().filter(AppUser::isActive).count())
                        .totalFileCount(items.stream().filter(DriveItem::isFile).count())
                        .totalFolderCount(items.stream().filter(DriveItem::isFolder).count())
                        .totalSharedFileCount(items.stream().filter(DriveItem::isSharedFile).count())
                        .totalTrashedCount(items.stream().filter(DriveItem::isTrashed).count())
                        .totalUsedBytes(items.stream().filter(DriveItem::isFile).mapToLong(DriveItem::getFileSize).sum())
                        .build())
                .users(userResponses)
                .build();
    }

    public DriveDtos.StorageAnalyticsResponse getStorageAnalytics(boolean admin) {
        ensureAdmin(admin);
        List<DriveItem> items = driveItemRepository.findAll();
        List<DriveDtos.AdminUserResponse> users = appUserRepository.findAllByOrderByIdAsc().stream()
                .map(user -> toAdminUserResponse(user, driveItemRepository.findAllByOwner_Id(user.getId())))
                .sorted(Comparator.comparing(DriveDtos.AdminUserResponse::usedBytes).reversed())
                .toList();

        long providerCapacityBytes = getAdminConfig().getProviderCapacityBytes();
        long providerUsedBytes = items.stream().filter(DriveItem::isFile).mapToLong(DriveItem::getFileSize).sum();
        long providerRemainingBytes = Math.max(providerCapacityBytes - providerUsedBytes, 0L);
        double providerUsagePercent = providerCapacityBytes <= 0
                ? 0.0
                : (providerUsedBytes * 100.0) / providerCapacityBytes;

        List<String> issues = new ArrayList<>();
        long missingObjectCount = items.stream()
                .filter(DriveItem::isFile)
                .filter(item -> item.getStoragePath() != null)
                .filter(item -> !driveStorageService.objectExists(item.getStoragePath()))
                .count();
        if (missingObjectCount > 0) {
            issues.add("스토리지에 없는 파일 객체가 " + missingObjectCount + "건 있습니다.");
        }
        long negativeSizeCount = items.stream()
                .filter(DriveItem::isFile)
                .filter(item -> item.getFileSize() < 0)
                .count();
        if (negativeSizeCount > 0) {
            issues.add("음수 파일 크기 메타데이터가 " + negativeSizeCount + "건 있습니다.");
        }
        if (providerUsedBytes > providerCapacityBytes) {
            issues.add("설정된 제공 용량을 초과했습니다.");
        }
        if (issues.isEmpty()) {
            issues.add("무결성 검사에서 즉시 확인된 문제는 없습니다.");
        }

        return DriveDtos.StorageAnalyticsResponse.builder()
                .providerCapacityBytes(providerCapacityBytes)
                .providerUsedBytes(providerUsedBytes)
                .providerRemainingBytes(providerRemainingBytes)
                .providerUsagePercent(providerUsagePercent)
                .issueCount(issues.size())
                .issues(issues)
                .users(users)
                .build();
    }

    @Transactional
    public DriveDtos.AdminUserResponse updateUserStatus(boolean admin, Long userId, boolean active) {
        ensureAdmin(admin);
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("사용자를 찾지 못했습니다."));
        user.setActive(active);
        return toAdminUserResponse(user, driveItemRepository.findAllByOwner_Id(user.getId()));
    }

    @Transactional
    public DriveDtos.StorageAnalyticsResponse updateProviderCapacity(boolean admin, Long providerCapacityBytes) {
        ensureAdmin(admin);
        if (providerCapacityBytes == null || providerCapacityBytes <= 0) {
            throw new BadRequestException("저장소 용량은 1바이트 이상이어야 합니다.");
        }

        DriveAdminConfig config = getAdminConfig();
        config.setProviderCapacityBytes(providerCapacityBytes);
        driveAdminConfigRepository.save(config);
        return getStorageAnalytics(true);
    }

    private DriveAdminConfig getAdminConfig() {
        return driveAdminConfigRepository.findById(DriveAdminConfig.SINGLETON_ID)
                .orElseGet(() -> driveAdminConfigRepository.save(new DriveAdminConfig()));
    }

    private void ensureAdmin(boolean admin) {
        if (!admin) {
            throw new AccessDeniedException("관리자만 접근할 수 있습니다.");
        }
    }

    private DriveDtos.AdminUserResponse toAdminUserResponse(AppUser user, List<DriveItem> items) {
        long fileCount = items.stream().filter(DriveItem::isFile).count();
        long folderCount = items.stream().filter(DriveItem::isFolder).count();
        long sharedFileCount = items.stream().filter(DriveItem::isSharedFile).count();
        long trashedCount = items.stream().filter(DriveItem::isTrashed).count();
        long usedBytes = items.stream().filter(DriveItem::isFile).mapToLong(DriveItem::getFileSize).sum();

        return DriveDtos.AdminUserResponse.builder()
                .id(user.getId())
                .loginId(user.getLoginId())
                .displayName(user.getDisplayName())
                .active(user.isActive())
                .role(user.getRole() != null ? user.getRole().name() : "USER")
                .fileCount(fileCount)
                .folderCount(folderCount)
                .sharedFileCount(sharedFileCount)
                .trashedCount(trashedCount)
                .usedBytes(usedBytes)
                .build();
    }
}
