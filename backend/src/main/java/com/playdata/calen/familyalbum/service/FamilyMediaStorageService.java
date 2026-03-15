package com.playdata.calen.familyalbum.service;

import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.familyalbum.domain.FamilyMediaType;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
public class FamilyMediaStorageService {

    private static final long MAX_FILE_SIZE = 30L * 1024 * 1024;
    private static final Set<String> IMAGE_FILE_EXTENSIONS = Set.of(
            "jpg",
            "jpeg",
            "png",
            "webp",
            "gif",
            "bmp",
            "heic",
            "heif"
    );
    private static final Set<String> VIDEO_FILE_EXTENSIONS = Set.of(
            "mp4",
            "mov",
            "m4v",
            "webm",
            "avi",
            "mkv"
    );

    private final Path rootPath;
    private final String mediaObjectPrefix;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public FamilyMediaStorageService(
            @Value("${app.family.media-storage-path}") String mediaStoragePath,
            @Value("${app.family.media-object-prefix:family-media}") String mediaObjectPrefix,
            ObjectProvider<MinioClient> minioClientProvider,
            MinioProperties minioProperties
    ) {
        this.rootPath = Paths.get(mediaStoragePath).toAbsolutePath().normalize();
        this.mediaObjectPrefix = normalizeObjectPrefix(mediaObjectPrefix);
        this.minioClient = minioClientProvider.getIfAvailable();
        this.minioProperties = minioProperties;
    }

    public StoredFamilyMedia store(Long ownerId, Long categoryId, MultipartFile file) {
        validateFile(file);

        String originalFileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String safeFileName = sanitizeFileName(originalFileName);
        String storedFileName = UUID.randomUUID() + "-" + safeFileName;
        String localStoragePath = buildLocalStoragePath(ownerId, categoryId, storedFileName);
        FamilyMediaType mediaType = detectMediaType(file.getContentType(), originalFileName);

        if (isMinioEnabled()) {
            String objectKey = buildMinioObjectKey(ownerId, categoryId, storedFileName);
            try {
                return storeToMinio(file, originalFileName, storedFileName, objectKey, mediaType);
            } catch (BadRequestException exception) {
                log.warn(
                        "MinIO family upload failed for ownerId={}, categoryId={}. Falling back to local storage. Reason={}",
                        ownerId,
                        categoryId,
                        exception.getMessage()
                );
                return storeToLocal(file, originalFileName, storedFileName, localStoragePath, mediaType);
            }
        }

        return storeToLocal(file, originalFileName, storedFileName, localStoragePath, mediaType);
    }

    public Resource loadAsResource(String storagePath) {
        if (!StringUtils.hasText(storagePath)) {
            throw new BadRequestException("파일 경로가 비어 있습니다.");
        }

        if (isMinioObject(storagePath)) {
            if (!isMinioEnabled()) {
                throw new BadRequestException("MinIO가 설정되지 않았습니다.");
            }
            return loadFromMinio(storagePath);
        }

        return loadFromLocal(storagePath);
    }

    public void deleteQuietly(String storagePath) {
        if (!StringUtils.hasText(storagePath)) {
            return;
        }

        if (isMinioObject(storagePath)) {
            if (isMinioEnabled()) {
                deleteFromMinioQuietly(storagePath);
            }
            return;
        }

        deleteFromLocalQuietly(storagePath);
    }

    private StoredFamilyMedia storeToLocal(
            MultipartFile file,
            String originalFileName,
            String storedFileName,
            String localStoragePath,
            FamilyMediaType mediaType
    ) {
        Path targetPath = rootPath.resolve(Path.of(localStoragePath)).normalize();

        try {
            Files.createDirectories(targetPath.getParent());
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new BadRequestException("파일을 저장하지 못했습니다.");
        }

        return new StoredFamilyMedia(
                originalFileName,
                storedFileName,
                localStoragePath,
                normalizeContentType(file.getContentType()),
                file.getSize(),
                mediaType
        );
    }

    private StoredFamilyMedia storeToMinio(
            MultipartFile file,
            String originalFileName,
            String storedFileName,
            String objectKey,
            FamilyMediaType mediaType
    ) {
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioProperties.getBucket_cloud())
                            .object(objectKey)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(normalizeContentType(file.getContentType()))
                            .build()
            );
        } catch (Exception exception) {
            throw new BadRequestException(buildStorageErrorMessage("파일을 저장하지 못했습니다.", exception));
        }

        return new StoredFamilyMedia(
                originalFileName,
                storedFileName,
                objectKey,
                normalizeContentType(file.getContentType()),
                file.getSize(),
                mediaType
        );
    }

    private Resource loadFromLocal(String storagePath) {
        try {
            Path filePath = rootPath.resolve(storagePath).normalize();
            if (!filePath.startsWith(rootPath)) {
                throw new BadRequestException("잘못된 파일 경로입니다.");
            }
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new BadRequestException("파일을 찾을 수 없습니다.");
            }
            return resource;
        } catch (IOException exception) {
            throw new BadRequestException("파일을 불러오지 못했습니다.");
        }
    }

    private Resource loadFromMinio(String storagePath) {
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(minioProperties.getBucket_cloud())
                        .object(storagePath)
                        .build())) {
            return new ByteArrayResource(inputStream.readAllBytes());
        } catch (Exception exception) {
            throw new BadRequestException(buildStorageErrorMessage("파일을 불러오지 못했습니다.", exception));
        }
    }

    private void deleteFromLocalQuietly(String storagePath) {
        try {
            Path filePath = rootPath.resolve(storagePath).normalize();
            if (filePath.startsWith(rootPath)) {
                Files.deleteIfExists(filePath);
            }
        } catch (IOException ignored) {
            // Keep cleanup resilient.
        }
    }

    private void deleteFromMinioQuietly(String storagePath) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(minioProperties.getBucket_cloud())
                            .object(storagePath)
                            .build()
            );
        } catch (Exception ignored) {
            // Keep cleanup resilient.
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("업로드할 파일을 선택하세요.");
        }
        if (!StringUtils.hasText(file.getOriginalFilename())) {
            throw new BadRequestException("파일 이름이 필요합니다.");
        }
        if (file.getSize() <= 0) {
            throw new BadRequestException("업로드할 파일을 선택하세요.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BadRequestException("사진과 동영상은 30MB 이하만 업로드할 수 있습니다.");
        }

        detectMediaType(file.getContentType(), file.getOriginalFilename());
    }

    private FamilyMediaType detectMediaType(String contentType, String originalFileName) {
        String normalizedContentType = normalizeContentType(contentType);
        String extension = extractExtension(originalFileName);

        if (normalizedContentType.startsWith("image/") || IMAGE_FILE_EXTENSIONS.contains(extension)) {
            return FamilyMediaType.PHOTO;
        }
        if (normalizedContentType.startsWith("video/") || VIDEO_FILE_EXTENSIONS.contains(extension)) {
            return FamilyMediaType.VIDEO;
        }

        throw new BadRequestException("사진과 동영상 파일만 업로드할 수 있습니다.");
    }

    private String sanitizeFileName(String fileName) {
        String sanitized = fileName.replaceAll("[^a-zA-Z0-9._-]", "-");
        return sanitized.isBlank() ? "file" : sanitized.toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String contentType) {
        return contentType == null ? "application/octet-stream" : contentType.toLowerCase(Locale.ROOT);
    }

    private boolean isMinioEnabled() {
        return minioClient != null
                && StringUtils.hasText(minioProperties.getEndpoint())
                && StringUtils.hasText(minioProperties.getAccessKey())
                && StringUtils.hasText(minioProperties.getSecretKey())
                && StringUtils.hasText(minioProperties.getBucket_cloud());
    }

    private boolean isMinioObject(String storagePath) {
        return storagePath.startsWith(mediaObjectPrefix + "/");
    }

    private String buildLocalStoragePath(Long ownerId, Long categoryId, String storedFileName) {
        return String.join(
                "/",
                String.valueOf(ownerId),
                String.valueOf(categoryId),
                storedFileName
        );
    }

    private String buildMinioObjectKey(Long ownerId, Long categoryId, String storedFileName) {
        return String.join(
                "/",
                mediaObjectPrefix,
                String.valueOf(ownerId),
                String.valueOf(categoryId),
                storedFileName
        );
    }

    private String normalizeObjectPrefix(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return "family-media";
        }

        return normalized
                .replace('\\', '/')
                .replaceAll("^/+", "")
                .replaceAll("/+$", "")
                .replaceAll("/+", "/");
    }

    private String extractExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }

        return fileName.substring(dotIndex + 1).trim().toLowerCase(Locale.ROOT);
    }

    private String buildStorageErrorMessage(String defaultMessage, Exception exception) {
        if (exception instanceof ResponseStatusException responseStatusException) {
            String statusReason = responseStatusException.getReason();
            if (StringUtils.hasText(statusReason)) {
                return defaultMessage + " (" + statusReason + ")";
            }
            return defaultMessage + " (" + responseStatusException.getStatusCode() + ")";
        }
        String detail = exception.getMessage();
        if (!StringUtils.hasText(detail)) {
            return defaultMessage;
        }
        return defaultMessage + " (" + detail + ")";
    }

    public record StoredFamilyMedia(
            String originalFileName,
            String storedFileName,
            String storagePath,
            String contentType,
            long fileSize,
            FamilyMediaType mediaType
    ) {
    }
}
