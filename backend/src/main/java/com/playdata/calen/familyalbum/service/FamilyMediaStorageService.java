package com.playdata.calen.familyalbum.service;

import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.media.ImageThumbnailService;
import com.playdata.calen.familyalbum.domain.FamilyMediaType;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FamilyMediaStorageService {

    private static final long MAX_FILE_SIZE = 30L * 1024 * 1024;
    private static final int HEADER_BYTES = 32;

    private final Path rootPath;
    private final String mediaObjectPrefix;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final ImageThumbnailService imageThumbnailService;

    public FamilyMediaStorageService(
            @Value("${app.family.media-storage-path}") String mediaStoragePath,
            @Value("${app.family.media-object-prefix:family-media}") String mediaObjectPrefix,
            ObjectProvider<MinioClient> minioClientProvider,
            MinioProperties minioProperties,
            ImageThumbnailService imageThumbnailService
    ) {
        this.rootPath = Paths.get(mediaStoragePath).toAbsolutePath().normalize();
        this.mediaObjectPrefix = normalizeObjectPrefix(mediaObjectPrefix);
        this.minioClient = minioClientProvider.getIfAvailable();
        this.minioProperties = minioProperties;
        this.imageThumbnailService = imageThumbnailService;
    }

    public StoredFamilyMedia store(Long ownerId, Long categoryId, MultipartFile file) {
        DetectedUpload detectedUpload = validateFile(file);

        String originalFileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String safeFileName = sanitizeFileName(originalFileName);
        String storedFileName = UUID.randomUUID() + "-" + safeFileName;
        String localStoragePath = buildLocalStoragePath(ownerId, categoryId, storedFileName);

        if (isMinioEnabled()) {
            String objectKey = buildMinioObjectKey(ownerId, categoryId, storedFileName);
            try {
                return storeToMinio(file, originalFileName, storedFileName, objectKey, detectedUpload);
            } catch (BadRequestException exception) {
                return storeToLocal(file, originalFileName, storedFileName, localStoragePath, detectedUpload);
            }
        }

        return storeToLocal(file, originalFileName, storedFileName, localStoragePath, detectedUpload);
    }

    public Resource loadAsResource(String storagePath) {
        if (!StringUtils.hasText(storagePath)) {
            throw new BadRequestException("파일 경로가 비어 있습니다.");
        }

        if (isMinioObject(storagePath)) {
            if (!isMinioEnabled()) {
                throw new BadRequestException("MinIO가 설정되어 있지 않습니다.");
            }
            return loadFromMinio(storagePath);
        }

        return loadFromLocal(storagePath);
    }

    public ThumbnailContent loadThumbnail(String storagePath, String contentType, Integer requestedWidth) {
        if (!StringUtils.hasText(storagePath) || !StringUtils.hasText(contentType) || !contentType.startsWith("image/")) {
            return null;
        }

        return imageThumbnailService.createThumbnail(loadAsResource(storagePath), contentType, requestedWidth)
                .map(thumbnail -> new ThumbnailContent(new ByteArrayResource(thumbnail.bytes()), thumbnail.contentType()))
                .orElse(null);
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
            DetectedUpload detectedUpload
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
                detectedUpload.contentType(),
                file.getSize(),
                detectedUpload.mediaType()
        );
    }

    private StoredFamilyMedia storeToMinio(
            MultipartFile file,
            String originalFileName,
            String storedFileName,
            String objectKey,
            DetectedUpload detectedUpload
    ) {
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioProperties.getBucket_cloud())
                            .object(objectKey)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(detectedUpload.contentType())
                            .build()
            );
        } catch (Exception exception) {
            throw new BadRequestException("파일을 저장하지 못했습니다.");
        }

        return new StoredFamilyMedia(
                originalFileName,
                storedFileName,
                objectKey,
                detectedUpload.contentType(),
                file.getSize(),
                detectedUpload.mediaType()
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
        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(minioProperties.getBucket_cloud())
                            .object(storagePath)
                            .build()
            );
            return new MinioObjectResource(minioClient, minioProperties.getBucket_cloud(), storagePath, stat);
        } catch (Exception exception) {
            throw new BadRequestException("파일을 불러오지 못했습니다.");
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

    private DetectedUpload validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("업로드할 파일을 선택해 주세요.");
        }
        if (!StringUtils.hasText(file.getOriginalFilename())) {
            throw new BadRequestException("파일 이름은 필수입니다.");
        }
        if (file.getSize() <= 0) {
            throw new BadRequestException("업로드할 파일을 선택해 주세요.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BadRequestException("사진과 동영상은 30MB 이하만 업로드할 수 있습니다.");
        }

        DetectedUpload detectedUpload = detectUpload(file.getContentType(), file.getOriginalFilename());
        validateBinarySignature(file, detectedUpload.contentType());
        return detectedUpload;
    }

    private DetectedUpload detectUpload(String contentType, String originalFileName) {
        String extension = extractExtension(originalFileName);
        String normalizedContentType = normalizeContentType(contentType);
        DetectedUpload inferredUpload = inferUploadByExtension(extension);

        if (inferredUpload == null) {
            throw new BadRequestException("JPG, PNG, WEBP, GIF, BMP, MP4, M4V, MOV, WEBM 파일만 업로드할 수 있습니다.");
        }

        if ("application/octet-stream".equals(normalizedContentType)) {
            return inferredUpload;
        }
        if (!inferredUpload.contentType().equals(normalizedContentType)) {
            throw new BadRequestException("파일 확장자와 Content-Type이 일치해야 합니다.");
        }
        return inferredUpload;
    }

    private DetectedUpload inferUploadByExtension(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> new DetectedUpload(FamilyMediaType.PHOTO, "image/jpeg");
            case "png" -> new DetectedUpload(FamilyMediaType.PHOTO, "image/png");
            case "webp" -> new DetectedUpload(FamilyMediaType.PHOTO, "image/webp");
            case "gif" -> new DetectedUpload(FamilyMediaType.PHOTO, "image/gif");
            case "bmp" -> new DetectedUpload(FamilyMediaType.PHOTO, "image/bmp");
            case "mp4", "m4v" -> new DetectedUpload(FamilyMediaType.VIDEO, "video/mp4");
            case "mov" -> new DetectedUpload(FamilyMediaType.VIDEO, "video/quicktime");
            case "webm" -> new DetectedUpload(FamilyMediaType.VIDEO, "video/webm");
            default -> null;
        };
    }

    private void validateBinarySignature(MultipartFile file, String contentType) {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = readHeader(inputStream);
            if (!matchesSignature(header, contentType)) {
                throw new BadRequestException("업로드한 파일 내용이 파일 형식과 일치하지 않습니다.");
            }
        } catch (IOException exception) {
            throw new BadRequestException("업로드한 파일을 읽지 못했습니다.");
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
            case "video/mp4" -> hasAsciiAt(header, 4, "ftyp");
            case "video/quicktime" -> hasAsciiAt(header, 4, "ftyp") && hasAsciiAt(header, 8, "qt  ");
            case "video/webm" -> startsWith(header, (byte) 0x1A, (byte) 0x45, (byte) 0xDF, (byte) 0xA3);
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

    private String sanitizeFileName(String fileName) {
        String sanitized = fileName.replaceAll("[^a-zA-Z0-9._-]", "-");
        return sanitized.isBlank() ? "file" : sanitized.toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String contentType) {
        return contentType == null ? "application/octet-stream" : contentType.trim().toLowerCase(Locale.ROOT);
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

    public record StoredFamilyMedia(
            String originalFileName,
            String storedFileName,
            String storagePath,
            String contentType,
            long fileSize,
            FamilyMediaType mediaType
    ) {
    }

    public record ThumbnailContent(
            Resource resource,
            String contentType
    ) {
    }

    private record DetectedUpload(
            FamilyMediaType mediaType,
            String contentType
    ) {
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
}
