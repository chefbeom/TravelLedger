package com.playdata.calen.drive.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveProfileSettings;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.drive.repository.DriveProfileSettingsRepository;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional(readOnly = true)
public class DriveProfileService {

    private final AppUserRepository appUserRepository;
    private final DriveProfileSettingsRepository driveProfileSettingsRepository;
    private final DriveItemRepository driveItemRepository;
    private final DriveStorageService driveStorageService;

    public DriveProfileService(
            AppUserRepository appUserRepository,
            DriveProfileSettingsRepository driveProfileSettingsRepository,
            DriveItemRepository driveItemRepository,
            DriveStorageService driveStorageService
    ) {
        this.appUserRepository = appUserRepository;
        this.driveProfileSettingsRepository = driveProfileSettingsRepository;
        this.driveItemRepository = driveItemRepository;
        this.driveStorageService = driveStorageService;
    }

    public DriveDtos.ProfileSettingsResponse getSettings(Long userId) {
        AppUser user = getUser(userId);
        DriveProfileSettings settings = getOrCreateSettings(user);
        return toSettingsResponse(user, settings);
    }

    @Transactional
    public DriveDtos.ProfileSettingsResponse updateSettings(Long userId, DriveDtos.ProfileSettingsUpdateRequest request) {
        AppUser user = getUser(userId);
        DriveProfileSettings settings = getOrCreateSettings(user);

        if (request == null) {
            throw new BadRequestException("변경할 설정이 없습니다.");
        }

        if (StringUtils.hasText(request.displayName())) {
            String nextDisplayName = request.displayName().trim();
            user.setDisplayName(nextDisplayName);
            settings.setDisplayName(nextDisplayName);
        }
        if (StringUtils.hasText(request.localeCode())) {
            settings.setLocaleCode(request.localeCode().trim().toUpperCase());
        }
        if (StringUtils.hasText(request.regionCode())) {
            settings.setRegionCode(request.regionCode().trim().toUpperCase());
        }
        if (request.marketingOptIn() != null) {
            settings.setMarketingOptIn(request.marketingOptIn());
        }
        if (request.privateProfile() != null) {
            settings.setPrivateProfile(request.privateProfile());
        }
        if (request.emailNotification() != null) {
            settings.setEmailNotification(request.emailNotification());
        }
        if (request.securityNotification() != null) {
            settings.setSecurityNotification(request.securityNotification());
        }

        return toSettingsResponse(user, settings);
    }

    @Transactional
    public DriveDtos.ProfileSettingsResponse uploadProfileImage(Long userId, MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new BadRequestException("업로드할 이미지를 선택해 주세요.");
        }

        String contentType = image.getContentType() == null ? "" : image.getContentType().trim().toLowerCase();
        if (!contentType.startsWith("image/")) {
            throw new BadRequestException("프로필 이미지는 이미지 파일만 업로드할 수 있습니다.");
        }

        AppUser user = getUser(userId);
        DriveProfileSettings settings = getOrCreateSettings(user);
        String objectKey = driveStorageService.buildProfileImageObjectKey(userId);
        try {
            driveStorageService.uploadObject(objectKey, image.getBytes(), image.getContentType());
        } catch (Exception exception) {
            throw new BadRequestException("프로필 이미지를 저장하지 못했습니다.");
        }
        settings.setProfileImagePath(objectKey);
        return toSettingsResponse(user, settings);
    }

    public DriveService.DriveFilePayload loadProfileImage(Long userId) {
        AppUser user = getUser(userId);
        DriveProfileSettings settings = getOrCreateSettings(user);
        if (!StringUtils.hasText(settings.getProfileImagePath())) {
            throw new NotFoundException("프로필 이미지가 없습니다.");
        }

        byte[] bytes = driveStorageService.loadObjectBytes(settings.getProfileImagePath());
        return new DriveService.DriveFilePayload(
                bytes,
                MediaType.IMAGE_PNG_VALUE,
                "profile-" + user.getLoginId() + ".png",
                bytes.length
        );
    }

    private DriveDtos.ProfileSettingsResponse toSettingsResponse(AppUser user, DriveProfileSettings settings) {
        long driveUsedBytes = driveItemRepository.findAllByOwner_Id(user.getId()).stream()
                .filter(DriveItem::isFile)
                .mapToLong(DriveItem::getFileSize)
                .sum();
        long driveFileCount = driveItemRepository.findAllByOwner_Id(user.getId()).stream()
                .filter(DriveItem::isFile)
                .count();

        return DriveDtos.ProfileSettingsResponse.builder()
                .userId(user.getId())
                .loginId(user.getLoginId())
                .displayName(settings.getDisplayName())
                .role(user.getRole() != null ? user.getRole().name() : "USER")
                .active(user.isActive())
                .localeCode(settings.getLocaleCode())
                .regionCode(settings.getRegionCode())
                .marketingOptIn(settings.isMarketingOptIn())
                .privateProfile(settings.isPrivateProfile())
                .emailNotification(settings.isEmailNotification())
                .securityNotification(settings.isSecurityNotification())
                .profileImageUrl(StringUtils.hasText(settings.getProfileImagePath())
                        ? "/api/feater/settings/me/profile-image"
                        : null)
                .driveUsedBytes(driveUsedBytes)
                .driveFileCount(driveFileCount)
                .createdAt(settings.getCreatedAt())
                .updatedAt(settings.getUpdatedAt())
                .build();
    }

    private AppUser getUser(Long userId) {
        return appUserRepository.findById(userId)
                .filter(AppUser::isActive)
                .orElseThrow(() -> new NotFoundException("사용자를 찾지 못했습니다."));
    }

    private DriveProfileSettings getOrCreateSettings(AppUser user) {
        return driveProfileSettingsRepository.findByUser_Id(user.getId())
                .orElseGet(() -> {
                    DriveProfileSettings created = new DriveProfileSettings();
                    created.setUser(user);
                    created.setDisplayName(user.getDisplayName());
                    return driveProfileSettingsRepository.save(created);
                });
    }
}
