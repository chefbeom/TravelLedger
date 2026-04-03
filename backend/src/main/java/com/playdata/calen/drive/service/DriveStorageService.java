package com.playdata.calen.drive.service;

import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.drive.dto.DriveDtos;
import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class DriveStorageService {

    private final ObjectProvider<MinioClient> minioClientProvider;
    private final MinioProperties minioProperties;

    public List<DriveDtos.UploadChunkResponse> initUpload(Long ownerId, List<DriveDtos.UploadInitRequest> requests) {
        ensureStorageConfigured();
        if (ownerId == null || ownerId <= 0 || requests == null || requests.isEmpty()) {
            throw new BadRequestException("업로드할 파일 정보를 확인해 주세요.");
        }

        List<DriveDtos.UploadChunkResponse> responses = new ArrayList<>();
        for (DriveDtos.UploadInitRequest request : requests) {
            String originName = normalizeOriginName(request.fileOriginName());
            String extension = normalizeExtension(request.fileFormat(), originName);
            String storedName = buildStoredName(extension);
            String objectKey = "drive/" + ownerId + "/" + storedName;
            String contentType = normalizeContentType(request.contentType());

            responses.add(DriveDtos.UploadChunkResponse.builder()
                    .fileOriginName(originName)
                    .fileSaveName(storedName)
                    .fileFormat(extension)
                    .fileSize(Math.max(0L, request.fileSize() == null ? 0L : request.fileSize()))
                    .contentType(contentType)
                    .parentId(request.parentId())
                    .relativePath(request.relativePath())
                    .lastModified(request.lastModified())
                    .presignedUploadUrl(generateUploadUrl(objectKey))
                    .presignedUrlExpiresIn(minioProperties.getPresignedUrlExpirySeconds())
                    .objectKey(objectKey)
                    .finalObjectKey(objectKey)
                    .partitionIndex(1)
                    .partitionCount(1)
                    .partitioned(false)
                    .uploaded(false)
                    .build());
        }
        return responses;
    }

    public DriveDtos.UploadCompleteResponse completeUpload(DriveDtos.UploadCompleteRequest request) {
        ensureStorageConfigured();
        if (request == null || !StringUtils.hasText(request.finalObjectKey())) {
            throw new BadRequestException("업로드 완료 정보를 확인해 주세요.");
        }
        ensureObjectExists(request.finalObjectKey());

        return DriveDtos.UploadCompleteResponse.builder()
                .fileOriginName(normalizeOriginName(request.fileOriginName()))
                .fileSaveName(extractStoredName(request.finalObjectKey()))
                .fileFormat(normalizeExtension(request.fileFormat(), request.fileOriginName()))
                .finalObjectKey(request.finalObjectKey())
                .build();
    }

    public DriveDtos.ActionResponse abortUpload(DriveDtos.UploadAbortRequest request) {
        ensureStorageConfigured();
        List<String> targets = new ArrayList<>();
        if (request != null) {
            if (StringUtils.hasText(request.finalObjectKey())) {
                targets.add(request.finalObjectKey());
            }
            if (request.chunkObjectKeys() != null) {
                targets.addAll(request.chunkObjectKeys().stream().filter(StringUtils::hasText).toList());
            }
        }
        deleteObjects(targets);
        return DriveDtos.ActionResponse.builder()
                .action("abort-upload")
                .affectedCount(targets.size())
                .build();
    }

    public byte[] loadObjectBytes(String objectKey) {
        ensureStorageConfigured();
        try (InputStream inputStream = minioClient().getObject(
                GetObjectArgs.builder()
                        .bucket(resolveBucket())
                        .object(objectKey)
                        .build())) {
            return inputStream.readAllBytes();
        } catch (Exception exception) {
            throw new BadRequestException("파일을 불러오지 못했습니다.");
        }
    }

    public long resolveObjectSize(String objectKey) {
        ensureStorageConfigured();
        try {
            return minioClient().statObject(
                    StatObjectArgs.builder()
                            .bucket(resolveBucket())
                            .object(objectKey)
                            .build()
            ).size();
        } catch (Exception exception) {
            throw new BadRequestException("저장된 파일 정보를 찾지 못했습니다.");
        }
    }

    public boolean objectExists(String objectKey) {
        ensureStorageConfigured();
        try {
            minioClient().statObject(
                    StatObjectArgs.builder()
                            .bucket(resolveBucket())
                            .object(objectKey)
                            .build()
            );
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    public void ensureObjectExists(String objectKey) {
        if (!objectExists(objectKey)) {
            throw new BadRequestException("업로드된 파일을 찾지 못했습니다.");
        }
    }

    public void deleteObject(String objectKey) {
        if (!StringUtils.hasText(objectKey) || !supportsStorage()) {
            return;
        }
        try {
            minioClient().removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(resolveBucket())
                            .object(objectKey)
                            .build()
            );
        } catch (Exception ignored) {
            // Deleting a missing object should not break the flow.
        }
    }

    public void deleteObjects(List<String> objectKeys) {
        if (objectKeys == null) {
            return;
        }
        objectKeys.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .forEach(this::deleteObject);
    }

    public void uploadObject(String objectKey, byte[] bytes, String contentType) {
        ensureStorageConfigured();
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)) {
            minioClient().putObject(
                    PutObjectArgs.builder()
                            .bucket(resolveBucket())
                            .object(objectKey)
                            .stream(inputStream, bytes.length, -1)
                            .contentType(contentType)
                            .build()
            );
        } catch (Exception exception) {
            throw new BadRequestException("파일을 저장하지 못했습니다.");
        }
    }

    public void copyObject(String sourceObjectKey, String targetObjectKey) {
        ensureStorageConfigured();
        try {
            minioClient().copyObject(
                    CopyObjectArgs.builder()
                            .bucket(resolveBucket())
                            .object(targetObjectKey)
                            .source(CopySource.builder()
                                    .bucket(resolveBucket())
                                    .object(sourceObjectKey)
                                    .build())
                            .build()
            );
        } catch (Exception exception) {
            throw new BadRequestException("공유 파일을 내 드라이브로 복사하지 못했습니다.");
        }
    }

    public String generateDownloadUrl(String objectKey) {
        ensureStorageConfigured();
        try {
            return rewritePublicUrl(minioClient().getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(resolveBucket())
                            .object(objectKey)
                            .expiry(minioProperties.getPresignedUrlExpirySeconds())
                            .build()
            ));
        } catch (Exception exception) {
            log.error("Failed to generate drive download URL. bucket={}, objectKey={}", resolveBucket(), objectKey, exception);
            throw new BadRequestException("다운로드 링크를 만들지 못했습니다.");
        }
    }

    public String buildProfileImageObjectKey(Long userId) {
        return "drive-profile/" + userId + "/profile.png";
    }

    public String resolveBucket() {
        String driveBucket = minioProperties.getBucket_cloud();
        if (StringUtils.hasText(driveBucket)) {
            return driveBucket;
        }
        return minioProperties.getBucket_work();
    }

    public boolean supportsStorage() {
        return minioClientProvider.getIfAvailable() != null
                && StringUtils.hasText(minioProperties.getEndpoint())
                && StringUtils.hasText(minioProperties.getAccessKey())
                && StringUtils.hasText(minioProperties.getSecretKey())
                && StringUtils.hasText(resolveBucket());
    }

    private MinioClient minioClient() {
        MinioClient client = minioClientProvider.getIfAvailable();
        if (client == null) {
            throw new BadRequestException("클라우드 저장소가 설정되지 않았습니다.");
        }
        return client;
    }

    private void ensureStorageConfigured() {
        if (!supportsStorage()) {
            throw new BadRequestException("클라우드 저장소 설정을 먼저 확인해 주세요.");
        }
        ensureBucketAvailable();
    }

    private void ensureBucketAvailable() {
        String bucket = resolveBucket();
        try {
            MinioClient client = minioClient();
            boolean exists = client.bucketExists(
                    BucketExistsArgs.builder()
                            .bucket(bucket)
                            .build()
            );
            if (!exists) {
                client.makeBucket(
                        MakeBucketArgs.builder()
                                .bucket(bucket)
                                .build()
                );
                log.info("Created missing drive bucket: {}", bucket);
            }
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("Failed to verify drive bucket {}", bucket, exception);
            throw new BadRequestException("드라이브 저장소 연결을 확인해 주세요.");
        }
    }

    private String generateUploadUrl(String objectKey) {
        try {
            return rewritePublicUrl(minioClient().getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(resolveBucket())
                            .object(objectKey)
                            .expiry(minioProperties.getPresignedUrlExpirySeconds())
                            .build()
            ));
        } catch (Exception exception) {
            log.error("Failed to generate drive upload URL. bucket={}, objectKey={}", resolveBucket(), objectKey, exception);
            throw new BadRequestException("업로드 URL을 만들지 못했습니다.");
        }
    }

    private String rewritePublicUrl(String sourceUrl) {
        String publicEndpoint = minioProperties.getPublicEndpoint();
        if (!StringUtils.hasText(publicEndpoint) || !StringUtils.hasText(sourceUrl)) {
            return sourceUrl;
        }

        try {
            URI publicBase = URI.create(publicEndpoint);
            URI signedUrl = URI.create(sourceUrl);
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
        } catch (IllegalArgumentException | URISyntaxException exception) {
            return sourceUrl;
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

    private String normalizeOriginName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized)) {
            throw new BadRequestException("파일 이름을 확인해 주세요.");
        }
        if (normalized.length() > 255 || normalized.contains("..") || normalized.contains("/") || normalized.contains("\\")) {
            throw new BadRequestException("허용되지 않는 파일 이름입니다.");
        }
        return normalized;
    }

    private String normalizeExtension(String fileFormat, String originName) {
        String normalized = StringUtils.hasText(fileFormat) ? fileFormat.trim().toLowerCase(Locale.ROOT) : "";
        if (!normalized.isEmpty()) {
            return normalized.replaceAll("^\\.+", "");
        }
        int dotIndex = originName.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < originName.length() - 1) {
            return originName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        }
        return "bin";
    }

    private String normalizeContentType(String contentType) {
        return StringUtils.hasText(contentType) ? contentType.trim() : "application/octet-stream";
    }

    private String buildStoredName(String extension) {
        String safeExtension = StringUtils.hasText(extension) ? extension : "bin";
        return UUID.randomUUID() + "." + safeExtension;
    }

    private String extractStoredName(String objectKey) {
        int separatorIndex = objectKey.lastIndexOf('/');
        return separatorIndex >= 0 ? objectKey.substring(separatorIndex + 1) : objectKey;
    }
}
