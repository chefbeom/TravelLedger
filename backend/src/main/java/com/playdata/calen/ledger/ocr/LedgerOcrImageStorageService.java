package com.playdata.calen.ledger.ocr;

import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerOcrImageStorageService {

    private static final String STORAGE_NOT_CONFIGURED_MESSAGE = "OCR image storage is not configured.";
    private static final String STORAGE_UNAVAILABLE_MESSAGE = "OCR image storage is unavailable.";

    private final ObjectProvider<MinioClient> minioClientProvider;
    private final MinioProperties minioProperties;

    public StoredImage store(Long ownerId, Long analysisId, MultipartFile file) {
        ensureStorageConfigured();
        if (ownerId == null || analysisId == null || file == null || file.isEmpty()) {
            throw new BadRequestException("OCR image storage request is invalid.");
        }
        String objectKey = buildObjectKey(ownerId, analysisId, file.getOriginalFilename());
        String contentType = normalizeContentType(file.getContentType());
        try (InputStream inputStream = file.getInputStream()) {
            minioClient().putObject(
                    PutObjectArgs.builder()
                            .bucket(resolveBucket())
                            .object(objectKey)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(contentType)
                            .build()
            );
            return new StoredImage(objectKey, LocalDateTime.now());
        } catch (Exception exception) {
            log.error("Failed to store ledger OCR image. bucket={}, objectKey={}", resolveBucket(), objectKey, exception);
            throw new ServiceUnavailableException(STORAGE_UNAVAILABLE_MESSAGE);
        }
    }

    public StoredImageContent load(String objectKey, String fileName, String contentType) {
        ensureStorageConfigured();
        if (!StringUtils.hasText(objectKey)) {
            throw new BadRequestException("Stored OCR image is missing.");
        }
        try (InputStream inputStream = minioClient().getObject(
                GetObjectArgs.builder()
                        .bucket(resolveBucket())
                        .object(objectKey)
                        .build())) {
            return new StoredImageContent(
                    inputStream.readAllBytes(),
                    normalizeContentType(contentType),
                    StringUtils.hasText(fileName) ? fileName.trim() : "ocr-image"
            );
        } catch (Exception exception) {
            log.error("Failed to load ledger OCR image. bucket={}, objectKey={}", resolveBucket(), objectKey, exception);
            throw new BadRequestException("Stored OCR image could not be loaded.");
        }
    }

    public boolean supportsStorage() {
        return minioClientProvider.getIfAvailable() != null
                && StringUtils.hasText(minioProperties.getEndpoint())
                && StringUtils.hasText(minioProperties.getAccessKey())
                && StringUtils.hasText(minioProperties.getSecretKey())
                && StringUtils.hasText(resolveBucket());
    }

    public String resolveBucket() {
        String cloudBucket = minioProperties.getBucket_cloud();
        if (StringUtils.hasText(cloudBucket)) {
            return cloudBucket;
        }
        return minioProperties.getBucket_work();
    }

    private void ensureStorageConfigured() {
        if (!supportsStorage()) {
            throw new ServiceUnavailableException(STORAGE_NOT_CONFIGURED_MESSAGE);
        }
    }

    private MinioClient minioClient() {
        MinioClient client = minioClientProvider.getIfAvailable();
        if (client == null) {
            throw new ServiceUnavailableException(STORAGE_NOT_CONFIGURED_MESSAGE);
        }
        return client;
    }

    private String buildObjectKey(Long ownerId, Long analysisId, String originalFileName) {
        String extension = resolveExtension(originalFileName);
        return "ledger-image-analysis/" + ownerId + "/" + analysisId + "/" + UUID.randomUUID() + extension;
    }

    private String resolveExtension(String originalFileName) {
        if (!StringUtils.hasText(originalFileName)) {
            return ".bin";
        }
        String normalized = originalFileName.trim().toLowerCase(Locale.ROOT);
        int dotIndex = normalized.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == normalized.length() - 1) {
            return ".bin";
        }
        String extension = normalized.substring(dotIndex + 1).replaceAll("[^a-z0-9]", "");
        if (!StringUtils.hasText(extension) || extension.length() > 12) {
            return ".bin";
        }
        return "." + extension;
    }

    private String normalizeContentType(String contentType) {
        return StringUtils.hasText(contentType) ? contentType.trim() : "application/octet-stream";
    }

    public record StoredImage(String objectKey, LocalDateTime storedAt) {
    }

    public record StoredImageContent(byte[] bytes, String contentType, String fileName) {
    }
}