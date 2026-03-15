package com.playdata.calen.travel.service;

import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.BadRequestException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class TravelMediaStorageService {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
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
    private static final Set<String> PDF_FILE_EXTENSIONS = Set.of("pdf");
    private static final Set<String> GPX_FILE_EXTENSIONS = Set.of("gpx", "xml");

    private final Path rootPath;
    private final String mediaObjectPrefix;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public TravelMediaStorageService(
            @Value("${app.travel.media-storage-path}") String mediaStoragePath,
            @Value("${app.travel.media-object-prefix:travel-media}") String mediaObjectPrefix,
            ObjectProvider<MinioClient> minioClientProvider,
            MinioProperties minioProperties
    ) {
        this.rootPath = Paths.get(mediaStoragePath).toAbsolutePath().normalize();
        this.mediaObjectPrefix = normalizeObjectPrefix(mediaObjectPrefix);
        this.minioClient = minioClientProvider.getIfAvailable();
        this.minioProperties = minioProperties;
    }

    public StoredTravelMedia store(Long ownerId, Long planId, Long recordId, MultipartFile file) {
        validateFile(file);

        String originalFileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String safeFileName = sanitizeFileName(originalFileName);
        String storedFileName = UUID.randomUUID() + "-" + safeFileName;
        String localStoragePath = buildLocalStoragePath(ownerId, planId, recordId, storedFileName);

        if (isMinioEnabled()) {
            String objectKey = buildMinioObjectKey(ownerId, planId, recordId, storedFileName);
            try {
                return storeToMinio(file, originalFileName, storedFileName, objectKey);
            } catch (BadRequestException exception) {
                log.warn(
                        "MinIO upload failed for ownerId={}, planId={}, recordId={}. Falling back to local storage. Reason={}",
                        ownerId,
                        planId,
                        recordId,
                        exception.getMessage()
                );
                return storeToLocal(file, originalFileName, storedFileName, localStoragePath);
            }
        }

        return storeToLocal(file, originalFileName, storedFileName, localStoragePath);
    }

    public StoredTravelMedia storeRouteGpx(Long ownerId, Long planId, Long routeId, MultipartFile file) {
        validateRouteGpxFile(file);

        String originalFileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "route.gpx";
        String safeFileName = sanitizeFileName(originalFileName);
        String storedFileName = UUID.randomUUID() + "-" + safeFileName;
        String localStoragePath = buildRouteLocalStoragePath(ownerId, planId, routeId, storedFileName);

        if (isMinioEnabled()) {
            String objectKey = buildRouteMinioObjectKey(ownerId, planId, routeId, storedFileName);
            try {
                return storeToMinio(file, originalFileName, storedFileName, objectKey);
            } catch (BadRequestException exception) {
                log.warn(
                        "MinIO GPX upload failed for ownerId={}, planId={}, routeId={}. Falling back to local storage. Reason={}",
                        ownerId,
                        planId,
                        routeId,
                        exception.getMessage()
                );
                return storeToLocal(file, originalFileName, storedFileName, localStoragePath);
            }
        }

        return storeToLocal(file, originalFileName, storedFileName, localStoragePath);
    }

    public boolean supportsPresignedUpload() {
        return isMinioEnabled();
    }

    public void validateUploadCandidates(List<UploadCandidate> files) {
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("Select a file to upload.");
        }

        files.forEach(file -> validateUploadCandidate(file.originalFileName(), file.contentType(), file.fileSize()));
    }

    public List<PresignedUpload> preparePresignedUploads(
            Long ownerId,
            Long planId,
            Long recordId,
            List<UploadCandidate> files
    ) {
        if (!supportsPresignedUpload()) {
            throw new BadRequestException("MinIO presigned upload is not available.");
        }

        validateUploadCandidates(files);

        return files.stream()
                .map(file -> createPresignedUpload(ownerId, planId, recordId, file))
                .toList();
    }

    public StoredTravelMedia completePresignedUpload(
            Long ownerId,
            Long planId,
            Long recordId,
            CompletedUpload upload
    ) {
        if (!supportsPresignedUpload()) {
            throw new BadRequestException("MinIO presigned upload is not available.");
        }

        validateUploadCandidate(upload.originalFileName(), upload.contentType(), upload.fileSize());
        validateObjectKey(ownerId, planId, recordId, upload.objectKey());

        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(minioProperties.getBucket_cloud())
                            .object(upload.objectKey())
                            .build()
            );

            if (stat.size() != upload.fileSize()) {
                throw new BadRequestException("Uploaded file verification failed.");
            }

            return new StoredTravelMedia(
                    upload.originalFileName(),
                    extractObjectName(upload.objectKey()),
                    upload.objectKey(),
                    normalizeContentType(upload.contentType()),
                    stat.size()
            );
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadRequestException("Uploaded file verification failed.");
        }
    }

    public Resource loadAsResource(String storagePath) {
        if (!StringUtils.hasText(storagePath)) {
            throw new BadRequestException("File path is empty.");
        }

        if (isMinioObject(storagePath)) {
            if (!isMinioEnabled()) {
                throw new BadRequestException("MinIO is not configured.");
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

    private StoredTravelMedia storeToLocal(
            MultipartFile file,
            String originalFileName,
            String storedFileName,
            String localStoragePath
    ) {
        Path relativePath = Path.of(localStoragePath);
        Path targetPath = rootPath.resolve(relativePath).normalize();

        try {
            Files.createDirectories(targetPath.getParent());
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new BadRequestException("Failed to store file.");
        }

        return new StoredTravelMedia(
                originalFileName,
                storedFileName,
                localStoragePath,
                normalizeContentType(file.getContentType()),
                file.getSize()
        );
    }

    private StoredTravelMedia storeToMinio(
            MultipartFile file,
            String originalFileName,
            String storedFileName,
            String objectKey
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
            throw new BadRequestException(buildStorageErrorMessage("Failed to store file.", exception));
        }

        return new StoredTravelMedia(
                originalFileName,
                storedFileName,
                objectKey,
                normalizeContentType(file.getContentType()),
                file.getSize()
        );
    }

    private Resource loadFromLocal(String storagePath) {
        try {
            Path filePath = rootPath.resolve(storagePath).normalize();
            if (!filePath.startsWith(rootPath)) {
                throw new BadRequestException("Invalid file path.");
            }
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new BadRequestException("File not found.");
            }
            return resource;
        } catch (IOException exception) {
            throw new BadRequestException("Failed to load file.");
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
            throw new BadRequestException(buildStorageErrorMessage("Failed to load file.", exception));
        }
    }

    private void deleteFromLocalQuietly(String storagePath) {
        try {
            Path filePath = rootPath.resolve(storagePath).normalize();
            if (filePath.startsWith(rootPath)) {
                Files.deleteIfExists(filePath);
            }
        } catch (IOException ignored) {
            // Keep database cleanup resilient even if the file is already missing.
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
            // Keep database cleanup resilient even if the object is already missing.
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Select a file to upload.");
        }

        validateUploadCandidate(file.getOriginalFilename(), file.getContentType(), file.getSize());
    }

    private void validateRouteGpxFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Select a GPX file to upload.");
        }

        String originalFileName = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFileName)) {
            throw new BadRequestException("GPX file name is required.");
        }
        if (file.getSize() <= 0) {
            throw new BadRequestException("Select a GPX file to upload.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BadRequestException("GPX files up to 10MB are allowed.");
        }

        String extension = extractExtension(originalFileName);
        if (!GPX_FILE_EXTENSIONS.contains(extension)) {
            throw new BadRequestException("Only .gpx files are allowed.");
        }
    }

    private String sanitizeFileName(String fileName) {
        String sanitized = fileName.replaceAll("[^a-zA-Z0-9._-]", "-");
        return sanitized.isBlank() ? "file" : sanitized.toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String contentType) {
        return contentType == null ? "application/octet-stream" : contentType.toLowerCase(Locale.ROOT);
    }

    private PresignedUpload createPresignedUpload(Long ownerId, Long planId, Long recordId, UploadCandidate file) {
        String safeFileName = sanitizeFileName(file.originalFileName());
        String storedFileName = UUID.randomUUID() + "-" + safeFileName;
        String objectKey = buildMinioObjectKey(ownerId, planId, recordId, storedFileName);

        try {
            String uploadUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(minioProperties.getBucket_cloud())
                            .object(objectKey)
                            .expiry(minioProperties.getPresignedUrlExpirySeconds())
                            .build()
            );

            return new PresignedUpload(
                    "PUT",
                    uploadUrl,
                    objectKey,
                    storedFileName,
                    file.originalFileName(),
                    normalizeContentType(file.contentType()),
                    file.fileSize()
            );
        } catch (Exception exception) {
            throw new BadRequestException(buildStorageErrorMessage("Failed to generate upload URL.", exception));
        }
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

    private String buildLocalStoragePath(Long ownerId, Long planId, Long recordId, String storedFileName) {
        return String.join(
                "/",
                String.valueOf(ownerId),
                String.valueOf(planId),
                String.valueOf(recordId),
                storedFileName
        );
    }

    private String buildMinioObjectKey(Long ownerId, Long planId, Long recordId, String storedFileName) {
        return String.join(
                "/",
                mediaObjectPrefix,
                String.valueOf(ownerId),
                String.valueOf(planId),
                String.valueOf(recordId),
                storedFileName
        );
    }

    private String buildRouteLocalStoragePath(Long ownerId, Long planId, Long routeId, String storedFileName) {
        return String.join(
                "/",
                String.valueOf(ownerId),
                String.valueOf(planId),
                "routes",
                String.valueOf(routeId),
                storedFileName
        );
    }

    private String buildRouteMinioObjectKey(Long ownerId, Long planId, Long routeId, String storedFileName) {
        return String.join(
                "/",
                mediaObjectPrefix,
                String.valueOf(ownerId),
                String.valueOf(planId),
                "routes",
                String.valueOf(routeId),
                storedFileName
        );
    }

    private void validateUploadCandidate(String originalFileName, String contentType, long fileSize) {
        if (!StringUtils.hasText(originalFileName)) {
            throw new BadRequestException("File name is required.");
        }
        if (fileSize <= 0) {
            throw new BadRequestException("Select a file to upload.");
        }
        if (fileSize > MAX_FILE_SIZE) {
            throw new BadRequestException("Files up to 10MB are allowed.");
        }

        String normalizedContentType = detectContentType(contentType, originalFileName);
        if (!isAllowedContentType(normalizedContentType)) {
            throw new BadRequestException("Only image files and PDF receipts are allowed.");
        }
    }

    private void validateObjectKey(Long ownerId, Long planId, Long recordId, String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            throw new BadRequestException("Object key is required.");
        }

        String expectedPrefix = String.join(
                "/",
                mediaObjectPrefix,
                String.valueOf(ownerId),
                String.valueOf(planId),
                String.valueOf(recordId)
        ) + "/";

        if (!objectKey.startsWith(expectedPrefix)) {
            throw new BadRequestException("Invalid uploaded file path.");
        }
    }

    private String extractObjectName(String objectKey) {
        int separatorIndex = objectKey.lastIndexOf('/');
        return separatorIndex >= 0 ? objectKey.substring(separatorIndex + 1) : objectKey;
    }

    private String normalizeObjectPrefix(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return "travel-media";
        }

        return normalized
                .replace('\\', '/')
                .replaceAll("^/+", "")
                .replaceAll("/+$", "")
                .replaceAll("/+", "/");
    }

    private boolean isAllowedContentType(String contentType) {
        return contentType.startsWith("image/") || "application/pdf".equals(contentType);
    }

    private String detectContentType(String contentType, String originalFileName) {
        String normalizedContentType = normalizeContentType(contentType);
        if (!"application/octet-stream".equals(normalizedContentType)) {
            return normalizedContentType;
        }

        String extension = extractExtension(originalFileName);
        if (IMAGE_FILE_EXTENSIONS.contains(extension)) {
            return "image/" + ("jpg".equals(extension) ? "jpeg" : extension);
        }
        if (PDF_FILE_EXTENSIONS.contains(extension)) {
            return "application/pdf";
        }

        return normalizedContentType;
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

    public record StoredTravelMedia(
            String originalFileName,
            String storedFileName,
            String storagePath,
            String contentType,
            long fileSize
    ) {
    }

    public record UploadCandidate(
            String originalFileName,
            String contentType,
            long fileSize
    ) {
    }

    public record CompletedUpload(
            String objectKey,
            String originalFileName,
            String contentType,
            long fileSize
    ) {
    }

    public record PresignedUpload(
            String method,
            String uploadUrl,
            String objectKey,
            String storedFileName,
            String originalFileName,
            String contentType,
            long fileSize
    ) {
    }
}
