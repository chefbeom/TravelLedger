package com.playdata.calen.travel.service;

import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.media.ImageThumbnailService;
import com.playdata.calen.common.media.PreparedThumbnailProfile;
import com.playdata.calen.common.exception.BadRequestException;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
public class TravelMediaStorageService {

    private static final long MAX_TRAVEL_MEDIA_FILE_SIZE = 15L * 1024 * 1024;
    private static final long MAX_GPX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int HEADER_BYTES = 32;
    private static final List<Integer> PREPARED_THUMBNAIL_WIDTHS = PreparedThumbnailProfile.defaultWidths();
    private static final Set<String> IMAGE_FILE_EXTENSIONS = Set.of(
            "jpg",
            "jpeg",
            "png",
            "webp",
            "gif",
            "bmp"
    );
    private static final Set<String> PDF_FILE_EXTENSIONS = Set.of("pdf");
    private static final Set<String> GPX_FILE_EXTENSIONS = Set.of("gpx", "xml");

    private final Path rootPath;
    private final String mediaObjectPrefix;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final boolean presignedUploadEnabled;
    private final ImageThumbnailService imageThumbnailService;

    public TravelMediaStorageService(
            @Value("${app.travel.media-storage-path}") String mediaStoragePath,
            @Value("${app.travel.media-object-prefix:travel-media}") String mediaObjectPrefix,
            @Value("${app.travel.presigned-upload-enabled:false}") boolean presignedUploadEnabled,
            ObjectProvider<MinioClient> minioClientProvider,
            MinioProperties minioProperties,
            ImageThumbnailService imageThumbnailService
    ) {
        this.rootPath = Paths.get(mediaStoragePath).toAbsolutePath().normalize();
        this.mediaObjectPrefix = normalizeObjectPrefix(mediaObjectPrefix);
        this.presignedUploadEnabled = presignedUploadEnabled;
        this.minioClient = minioClientProvider.getIfAvailable();
        this.minioProperties = minioProperties;
        this.imageThumbnailService = imageThumbnailService;
    }

    public StoredTravelMedia store(Long ownerId, Long planId, Long recordId, MultipartFile file) {
        String verifiedContentType = validateFile(file);
        byte[] sourceBytes = readFileBytes(file);

        String originalFileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String safeFileName = sanitizeFileName(originalFileName);
        String storedFileName = UUID.randomUUID() + "-" + safeFileName;
        String localStoragePath = buildLocalStoragePath(ownerId, planId, recordId, storedFileName);

        if (isMinioEnabled()) {
            String objectKey = buildMinioObjectKey(ownerId, planId, recordId, storedFileName);
            try {
                return storeToMinio(sourceBytes, originalFileName, storedFileName, objectKey, verifiedContentType);
            } catch (BadRequestException exception) {
                return storeToLocal(sourceBytes, originalFileName, storedFileName, localStoragePath, verifiedContentType);
            }
        }

        return storeToLocal(sourceBytes, originalFileName, storedFileName, localStoragePath, verifiedContentType);
    }

    public StoredTravelMedia storeRouteGpx(Long ownerId, Long planId, Long routeId, MultipartFile file) {
        validateRouteGpxFile(file);

        String originalFileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "route.gpx";
        String safeFileName = sanitizeFileName(originalFileName);
        String storedFileName = UUID.randomUUID() + "-" + safeFileName;
        String localStoragePath = buildRouteLocalStoragePath(ownerId, planId, routeId, storedFileName);
        byte[] sourceBytes = readFileBytes(file);

        if (isMinioEnabled()) {
            String objectKey = buildRouteMinioObjectKey(ownerId, planId, routeId, storedFileName);
            try {
                return storeToMinio(sourceBytes, originalFileName, storedFileName, objectKey, "application/gpx+xml");
            } catch (BadRequestException exception) {
                return storeToLocal(sourceBytes, originalFileName, storedFileName, localStoragePath, "application/gpx+xml");
            }
        }

        return storeToLocal(sourceBytes, originalFileName, storedFileName, localStoragePath, "application/gpx+xml");
    }

    public boolean supportsPresignedUpload() {
        return presignedUploadEnabled && isMinioEnabled();
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

        String verifiedContentType = validateUploadCandidate(
                upload.originalFileName(),
                upload.contentType(),
                upload.fileSize()
        );
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

            validateStoredObjectSignature(upload.objectKey(), verifiedContentType);
            prepareDerivedThumbnailsQuietly(upload.objectKey(), verifiedContentType);

            return new StoredTravelMedia(
                    upload.originalFileName(),
                    extractObjectName(upload.objectKey()),
                    upload.objectKey(),
                    verifiedContentType,
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

    public PreparedThumbnail loadPreparedThumbnail(String storagePath, String contentType, Integer requestedWidth) {
        if (!StringUtils.hasText(storagePath) || !StringUtils.hasText(contentType) || !contentType.startsWith("image/")) {
            return null;
        }

        String thumbnailPath = buildThumbnailStoragePath(
                storagePath,
                contentType,
                PreparedThumbnailProfile.selectWidth(requestedWidth)
        );
        try {
            return new PreparedThumbnail(loadAsResource(thumbnailPath), resolveThumbnailContentType(contentType));
        } catch (BadRequestException ignored) {
            return null;
        }
    }

    public PreparedThumbnail loadThumbnail(String storagePath, String contentType, Integer requestedWidth) {
        PreparedThumbnail preparedThumbnail = loadPreparedThumbnail(storagePath, contentType, requestedWidth);
        if (preparedThumbnail != null) {
            return preparedThumbnail;
        }
        if (!StringUtils.hasText(storagePath) || !StringUtils.hasText(contentType) || !contentType.startsWith("image/")) {
            return null;
        }

        if (!prepareDerivedThumbnails(storagePath, contentType)) {
            return null;
        }

        return loadPreparedThumbnail(storagePath, contentType, requestedWidth);
    }

    public ThumbnailPreparationStatus ensurePreparedThumbnails(String storagePath, String contentType) {
        if (!StringUtils.hasText(storagePath) || !StringUtils.hasText(contentType) || !contentType.startsWith("image/")) {
            return ThumbnailPreparationStatus.SKIPPED;
        }

        if (hasAllPreparedThumbnails(storagePath, contentType)) {
            return ThumbnailPreparationStatus.ALREADY_PRESENT;
        }

        return prepareDerivedThumbnails(storagePath, contentType)
                ? ThumbnailPreparationStatus.CREATED
                : ThumbnailPreparationStatus.FAILED;
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

    public void deleteImageWithThumbnailsQuietly(String storagePath, String contentType) {
        deleteQuietly(storagePath);

        if (!StringUtils.hasText(storagePath) || !StringUtils.hasText(contentType) || !contentType.startsWith("image/")) {
            return;
        }

        PREPARED_THUMBNAIL_WIDTHS.forEach(width -> deleteQuietly(buildThumbnailStoragePath(storagePath, contentType, width)));
    }

    private StoredTravelMedia storeToLocal(
            byte[] sourceBytes,
            String originalFileName,
            String storedFileName,
            String localStoragePath,
            String contentType
    ) {
        Path relativePath = Path.of(localStoragePath);
        Path targetPath = rootPath.resolve(relativePath).normalize();

        try {
            Files.createDirectories(targetPath.getParent());
            Files.write(targetPath, sourceBytes);
        } catch (IOException exception) {
            throw new BadRequestException("Failed to store file.");
        }

        prepareDerivedThumbnailsQuietly(localStoragePath, contentType, sourceBytes);

        return new StoredTravelMedia(
                originalFileName,
                storedFileName,
                localStoragePath,
                contentType,
                sourceBytes.length
        );
    }

    private StoredTravelMedia storeToMinio(
            byte[] sourceBytes,
            String originalFileName,
            String storedFileName,
            String objectKey,
            String contentType
    ) {
        try (InputStream inputStream = new java.io.ByteArrayInputStream(sourceBytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioProperties.getBucket_cloud())
                            .object(objectKey)
                            .stream(inputStream, sourceBytes.length, -1)
                            .contentType(contentType)
                            .build()
            );
        } catch (Exception exception) {
            throw new BadRequestException(buildStorageErrorMessage("Failed to store file."));
        }

        prepareDerivedThumbnailsQuietly(objectKey, contentType, sourceBytes);

        return new StoredTravelMedia(
                originalFileName,
                storedFileName,
                objectKey,
                contentType,
                sourceBytes.length
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
        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(minioProperties.getBucket_cloud())
                            .object(storagePath)
                            .build()
            );
            return new MinioObjectResource(minioClient, minioProperties.getBucket_cloud(), storagePath, stat);
        } catch (Exception exception) {
            throw new BadRequestException(buildStorageErrorMessage("Failed to load file."));
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

    private boolean hasAllPreparedThumbnails(String storagePath, String contentType) {
        return PREPARED_THUMBNAIL_WIDTHS.stream()
                .allMatch(width -> preparedThumbnailExists(storagePath, contentType, width));
    }

    private boolean preparedThumbnailExists(String storagePath, String contentType, int width) {
        String thumbnailPath = buildThumbnailStoragePath(storagePath, contentType, width);
        if (isMinioObject(thumbnailPath)) {
            return isMinioEnabled() && existsInMinio(thumbnailPath);
        }
        return existsInLocal(thumbnailPath);
    }

    private boolean existsInLocal(String storagePath) {
        try {
            Path filePath = rootPath.resolve(storagePath).normalize();
            return filePath.startsWith(rootPath) && Files.exists(filePath);
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean existsInMinio(String storagePath) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(minioProperties.getBucket_cloud())
                            .object(storagePath)
                            .build()
            );
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Select a file to upload.");
        }

        String verifiedContentType = validateUploadCandidate(file.getOriginalFilename(), file.getContentType(), file.getSize());
        validateBinarySignature(file, verifiedContentType);
        return verifiedContentType;
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
        if (file.getSize() > MAX_GPX_FILE_SIZE) {
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

    private byte[] readFileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new BadRequestException("Failed to read the uploaded file.");
        }
    }

    private String normalizeContentType(String contentType) {
        return contentType == null ? "application/octet-stream" : contentType.trim().toLowerCase(Locale.ROOT);
    }

    private void prepareDerivedThumbnailsQuietly(String storagePath, String contentType) {
        prepareDerivedThumbnails(storagePath, contentType);
    }

    private boolean prepareDerivedThumbnails(String storagePath, String contentType) {
        if (!StringUtils.hasText(storagePath) || !StringUtils.hasText(contentType) || !contentType.startsWith("image/")) {
            return false;
        }

        try (InputStream inputStream = loadAsResource(storagePath).getInputStream()) {
            prepareDerivedThumbnailsQuietly(storagePath, contentType, inputStream.readAllBytes());
            return true;
        } catch (Exception exception) {
            log.debug("Failed to prepare image thumbnails for {}", storagePath, exception);
            return false;
        }
    }

    private void prepareDerivedThumbnailsQuietly(String storagePath, String contentType, byte[] sourceBytes) {
        if (!StringUtils.hasText(storagePath) || !StringUtils.hasText(contentType) || !contentType.startsWith("image/") || sourceBytes == null || sourceBytes.length == 0) {
            return;
        }

        try {
            List<ImageThumbnailService.PreparedThumbnailContent> thumbnails = imageThumbnailService.createPreparedThumbnails(
                    sourceBytes,
                    contentType,
                    PREPARED_THUMBNAIL_WIDTHS
            );

            for (ImageThumbnailService.PreparedThumbnailContent thumbnail : thumbnails) {
                String thumbnailPath = buildThumbnailStoragePath(storagePath, contentType, thumbnail.width());
                if (isMinioObject(storagePath) && isMinioEnabled()) {
                    storeThumbnailToMinio(thumbnailPath, thumbnail);
                } else {
                    storeThumbnailToLocal(thumbnailPath, thumbnail);
                }
            }
        } catch (Exception exception) {
            log.debug("Failed to persist prepared thumbnails for {}", storagePath, exception);
        }
    }

    private void storeThumbnailToLocal(String storagePath, ImageThumbnailService.PreparedThumbnailContent thumbnail) throws IOException {
        Path relativePath = Path.of(storagePath);
        Path targetPath = rootPath.resolve(relativePath).normalize();
        Files.createDirectories(targetPath.getParent());
        Files.write(targetPath, thumbnail.bytes());
    }

    private void storeThumbnailToMinio(String storagePath, ImageThumbnailService.PreparedThumbnailContent thumbnail) throws Exception {
        try (InputStream inputStream = new java.io.ByteArrayInputStream(thumbnail.bytes())) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioProperties.getBucket_cloud())
                            .object(storagePath)
                            .stream(inputStream, thumbnail.bytes().length, -1)
                            .contentType(thumbnail.contentType())
                            .build()
            );
        }
    }

    private String buildThumbnailStoragePath(String storagePath, String contentType, int width) {
        int separatorIndex = storagePath.lastIndexOf('/');
        String directory = separatorIndex >= 0 ? storagePath.substring(0, separatorIndex) : "";
        String fileName = separatorIndex >= 0 ? storagePath.substring(separatorIndex + 1) : storagePath;
        String baseName = stripExtension(fileName);
        String extension = resolveThumbnailFileExtension(contentType);
        String thumbnailFileName = baseName + "." + extension;

        if (directory.isEmpty()) {
            return String.join("/", ".thumbs", String.valueOf(width), thumbnailFileName);
        }
        return String.join("/", directory, ".thumbs", String.valueOf(width), thumbnailFileName);
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }

    private String resolveThumbnailFileExtension(String contentType) {
        return preservesAlpha(contentType) ? "png" : "jpg";
    }

    private String resolveThumbnailContentType(String contentType) {
        return preservesAlpha(contentType) ? "image/png" : "image/jpeg";
    }

    private boolean preservesAlpha(String contentType) {
        String normalized = normalizeContentType(contentType);
        return "image/png".equals(normalized) || "image/gif".equals(normalized) || "image/webp".equals(normalized);
    }

    private PresignedUpload createPresignedUpload(Long ownerId, Long planId, Long recordId, UploadCandidate file) {
        String verifiedContentType = validateUploadCandidate(file.originalFileName(), file.contentType(), file.fileSize());
        String safeFileName = sanitizeFileName(file.originalFileName());
        String storedFileName = UUID.randomUUID() + "-" + safeFileName;
        String objectKey = buildMinioObjectKey(ownerId, planId, recordId, storedFileName);

        try {
            String uploadUrl = rewritePublicUploadUrl(minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(minioProperties.getBucket_cloud())
                            .object(objectKey)
                            .expiry(minioProperties.getPresignedUrlExpirySeconds())
                            .build()
            ));

            return new PresignedUpload(
                    "PUT",
                    uploadUrl,
                    objectKey,
                    storedFileName,
                    file.originalFileName(),
                    verifiedContentType,
                    file.fileSize()
            );
        } catch (Exception exception) {
            throw new BadRequestException(buildStorageErrorMessage("Failed to generate upload URL."));
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

    private String validateUploadCandidate(String originalFileName, String contentType, long fileSize) {
        if (!StringUtils.hasText(originalFileName)) {
            throw new BadRequestException("File name is required.");
        }
        if (fileSize <= 0) {
            throw new BadRequestException("Select a file to upload.");
        }
        if (fileSize > MAX_TRAVEL_MEDIA_FILE_SIZE) {
            throw new BadRequestException("Photos and receipts up to 15MB are allowed.");
        }

        return resolveAllowedContentType(originalFileName, contentType);
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

    private String rewritePublicUploadUrl(String uploadUrl) {
        String publicEndpoint = minioProperties.getPublicEndpoint();
        if (!StringUtils.hasText(publicEndpoint) || !StringUtils.hasText(uploadUrl)) {
            return uploadUrl;
        }

        try {
            URI publicBase = URI.create(publicEndpoint);
            URI signedUrl = URI.create(uploadUrl);
            String mergedPath = mergePath(publicBase.getPath(), signedUrl.getPath());
            return new URI(
                    publicBase.getScheme(),
                    publicBase.getUserInfo(),
                    publicBase.getHost(),
                    publicBase.getPort(),
                    mergedPath,
                    signedUrl.getQuery(),
                    signedUrl.getFragment()
            ).toString();
        } catch (IllegalArgumentException | java.net.URISyntaxException exception) {
            return uploadUrl;
        }
    }

    private String mergePath(String basePath, String signedPath) {
        String normalizedBase = StringUtils.hasText(basePath) && !"/".equals(basePath)
                ? basePath.replaceAll("/+$", "")
                : "";
        String normalizedSigned = StringUtils.hasText(signedPath) ? signedPath : "";
        if (!normalizedSigned.startsWith("/")) {
            normalizedSigned = "/" + normalizedSigned;
        }
        return normalizedBase + normalizedSigned;
    }

    private String resolveAllowedContentType(String originalFileName, String contentType) {
        String extension = extractExtension(originalFileName);
        String inferredContentType = inferContentTypeFromExtension(extension);
        if (inferredContentType == null) {
            throw new BadRequestException("Only JPG, PNG, WEBP, GIF, BMP images and PDF files are allowed.");
        }

        String normalizedContentType = normalizeContentType(contentType);
        if ("application/octet-stream".equals(normalizedContentType)) {
            return inferredContentType;
        }
        if (!inferredContentType.equals(normalizedContentType)) {
            throw new BadRequestException("File extension and content type must match.");
        }
        return inferredContentType;
    }

    private String inferContentTypeFromExtension(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "pdf" -> "application/pdf";
            default -> null;
        };
    }

    private void validateBinarySignature(MultipartFile file, String contentType) {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = readHeader(inputStream);
            if (!matchesSignature(header, contentType)) {
                throw new BadRequestException("Uploaded file contents do not match the file type.");
            }
        } catch (IOException exception) {
            throw new BadRequestException("Failed to read the uploaded file.");
        }
    }

    private void validateStoredObjectSignature(String objectKey, String contentType) {
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(minioProperties.getBucket_cloud())
                        .object(objectKey)
                        .build())) {
            byte[] header = readHeader(inputStream);
            if (!matchesSignature(header, contentType)) {
                throw new BadRequestException("Uploaded file contents do not match the file type.");
            }
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadRequestException("Uploaded file verification failed.");
        }
    }

    private byte[] readHeader(InputStream inputStream) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        int offset = 0;
        while (offset < HEADER_BYTES) {
            int read = inputStream.read(header, offset, HEADER_BYTES - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return Arrays.copyOf(header, offset);
    }

    private boolean matchesSignature(byte[] header, String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> startsWith(header, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
            case "image/png" -> startsWith(header, (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
                    (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A);
            case "image/gif" -> startsWith(header, "GIF87a") || startsWith(header, "GIF89a");
            case "image/bmp" -> startsWith(header, "BM");
            case "image/webp" -> startsWith(header, "RIFF") && hasAsciiAt(header, 8, "WEBP");
            case "application/pdf" -> startsWith(header, "%PDF-");
            default -> false;
        };
    }

    private boolean startsWith(byte[] header, String expected) {
        return startsWith(header, expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private boolean startsWith(byte[] header, byte... expected) {
        if (header.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index += 1) {
            if (header[index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAsciiAt(byte[] header, int offset, String expected) {
        byte[] bytes = expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (header.length < offset + bytes.length) {
            return false;
        }
        for (int index = 0; index < bytes.length; index += 1) {
            if (header[offset + index] != bytes[index]) {
                return false;
            }
        }
        return true;
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

    private String buildStorageErrorMessage(String defaultMessage) {
        return defaultMessage;
    }

    private static final class MinioObjectResource extends AbstractResource {

        private final MinioClient minioClient;
        private final String bucket;
        private final String objectKey;
        private final long contentLength;
        private final long lastModified;

        private MinioObjectResource(
                MinioClient minioClient,
                String bucket,
                String objectKey,
                StatObjectResponse stat
        ) {
            this.minioClient = minioClient;
            this.bucket = bucket;
            this.objectKey = objectKey;
            this.contentLength = stat.size();
            this.lastModified = stat.lastModified() != null
                    ? stat.lastModified().toInstant().toEpochMilli()
                    : 0L;
        }

        @Override
        public String getDescription() {
            return "MinIO object [" + bucket + "/" + objectKey + "]";
        }

        @Override
        public InputStream getInputStream() throws IOException {
            try {
                return minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectKey)
                                .build()
                );
            } catch (Exception exception) {
                throw new IOException("Failed to open MinIO object stream.", exception);
            }
        }

        @Override
        public String getFilename() {
            return StringUtils.getFilename(objectKey);
        }

        @Override
        public long contentLength() {
            return contentLength;
        }

        @Override
        public long lastModified() {
            return lastModified;
        }
    }

    public record StoredTravelMedia(
            String originalFileName,
            String storedFileName,
            String storagePath,
            String contentType,
            long fileSize
    ) {
    }

    public record PreparedThumbnail(
            Resource resource,
            String contentType
    ) {
    }

    public enum ThumbnailPreparationStatus {
        CREATED,
        ALREADY_PRESENT,
        FAILED,
        SKIPPED
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
