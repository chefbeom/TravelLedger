package com.playdata.calen.ledger.adapter.out.storage;

import com.playdata.calen.ledger.application.port.out.LedgerImageStoragePort;
import com.playdata.calen.ledger.domain.model.LedgerImageStorageData.*;

import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.storage.LedgerImageStorageMarker;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.ObjectWriteResponse;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@Slf4j
public class MinioLedgerImageStorageAdapter implements LedgerImageStoragePort {

    private static final String STORAGE_NOT_CONFIGURED_MESSAGE = "OCR image storage is not configured.";
    private static final String STORAGE_UNAVAILABLE_MESSAGE = "OCR image storage is unavailable.";

    private final ObjectProvider<MinioClient> minioClientProvider;
    private final MinioProperties minioProperties;
    @Value("${app.ledger.ocr.image-storage-id:}")
    private String imageStorageId;

    public StoredImage store(Long ownerId, Long analysisId, Upload file) {
        ensureStorageConfigured();
        if (ownerId == null || analysisId == null || file == null || file.empty()) {
            throw new BadRequestException("OCR image storage request is invalid.");
        }
        String objectKey = buildObjectKey(ownerId, analysisId, file.fileName());
        String contentType = normalizeContentType(file.contentType());
        String bucket = resolveBucket();
        MinioClient client = minioClient();
        String configuredId = imageStorageId == null ? "" : imageStorageId.trim();
        String storageId = LedgerImageStorageMarker.matches(client, bucket, configuredId) ? configuredId : null;
        try (InputStream inputStream = file.openStream()) {
            ObjectWriteResponse response = client.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .stream(inputStream, file.size(), -1)
                            .contentType(contentType)
                            .build()
            );
            String version = response == null ? null : response.versionId();
            // Keep even heavily JSON-escaped IDs within the encrypted task's 2 KiB payload limit.
            if (version == null || version.isBlank() || version.length() > 512
                    || version.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512
                    || version.chars().anyMatch(Character::isISOControl)) version = null;
            // ETag is an opaque comparison hint, not proof of ownership, content or a unique version.
            // Do not retain a fingerprint when the optional storage identity was not verified.
            String etag = storageId == null || response == null ? null : response.etag();
            if (etag != null && !etag.matches("[A-Za-z0-9_-]{1,128}")) etag = null;
            return new StoredImage(objectKey, LocalDateTime.now(), bucket, storageId, version, etag);
        } catch (Exception exception) {
            log.error("Failed to store ledger OCR image. bucket={}, objectKey={}", bucket, objectKey, exception);
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

    public void delete(String objectKey) {
        deleteFromBucket(resolveBucket(), objectKey);
    }

    @Override
    public void deleteFromBucket(String bucket, String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return;
        }
        ensureStorageConfigured();
        if (!StringUtils.hasText(bucket)) throw new BadRequestException("Stored OCR image bucket is missing.");
        try {
            minioClient().removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build()
            );
        } catch (Exception exception) {
            log.error("Failed to delete ledger OCR image. bucket={}, objectKey={}", bucket, objectKey, exception);
            throw new ServiceUnavailableException(STORAGE_UNAVAILABLE_MESSAGE);
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


}
