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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional(readOnly = true)
public class DriveProfileService {

    private static final long MAX_PROFILE_IMAGE_SIZE_BYTES = 5L * 1024L * 1024L;

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

        if (image.getSize() > MAX_PROFILE_IMAGE_SIZE_BYTES) {
            throw new BadRequestException("Profile images must not exceed 5 MB.");
        }

        String contentType = image.getContentType() == null ? "" : image.getContentType().trim().toLowerCase();
        if (!(MediaType.IMAGE_PNG_VALUE.equals(contentType)
                || MediaType.IMAGE_JPEG_VALUE.equals(contentType)
                || MediaType.IMAGE_GIF_VALUE.equals(contentType))) {
            throw new BadRequestException("Only PNG, JPEG, and GIF profile images are supported.");
        }

        byte[] imageBytes;
        try {
            imageBytes = image.getBytes();
        } catch (Exception exception) {
            throw new BadRequestException("Could not read the profile image.");
        }
        if (!hasAllowedImageSignature(contentType, imageBytes)) {
            throw new BadRequestException("Profile image content does not match its declared image type.");
        }
        byte[] sanitizedImageBytes = sanitizeProfileImage(imageBytes);

        AppUser user = getUser(userId);
        DriveProfileSettings settings = getOrCreateSettings(user);
        String objectKey = driveStorageService.buildProfileImageObjectKey(userId);
        try {
            driveStorageService.uploadObject(objectKey, sanitizedImageBytes, MediaType.IMAGE_PNG_VALUE);
        } catch (Exception exception) {
            throw new BadRequestException("Could not store the profile image.");
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

    private byte[] sanitizeProfileImage(byte[] sourceBytes) {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(sourceBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new BadRequestException("Profile image data could not be decoded.");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixelCount = (long) width * height;
                if (width <= 0 || height <= 0 || width > 4096 || height > 4096 || pixelCount > 16_000_000L) {
                    throw new BadRequestException("Profile image dimensions are too large.");
                }

                BufferedImage image = reader.read(0);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (image == null || !ImageIO.write(image, "png", output)) {
                    throw new BadRequestException("Profile image data could not be converted.");
                }
                return output.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new BadRequestException("Profile image data could not be decoded.");
        }
    }

    private boolean hasAllowedImageSignature(String contentType, byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        return switch (contentType) {
            case MediaType.IMAGE_PNG_VALUE -> bytes.length >= 8
                    && unsigned(bytes[0]) == 0x89
                    && bytes[1] == 0x50
                    && bytes[2] == 0x4E
                    && bytes[3] == 0x47
                    && bytes[4] == 0x0D
                    && bytes[5] == 0x0A
                    && bytes[6] == 0x1A
                    && bytes[7] == 0x0A;
            case MediaType.IMAGE_JPEG_VALUE -> bytes.length >= 3
                    && unsigned(bytes[0]) == 0xFF
                    && unsigned(bytes[1]) == 0xD8
                    && unsigned(bytes[2]) == 0xFF;
            case MediaType.IMAGE_GIF_VALUE -> bytes.length >= 6
                    && bytes[0] == 'G'
                    && bytes[1] == 'I'
                    && bytes[2] == 'F'
                    && bytes[3] == '8'
                    && (bytes[4] == '7' || bytes[4] == '9')
                    && bytes[5] == 'a';

            default -> false;
        };
    }

    private int unsigned(byte value) {
        return value & 0xFF;
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
