package com.playdata.calen.account.service;

import com.playdata.calen.account.dto.AdminMinioStorageSummaryResponse;
import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.BadRequestException;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class MinioBackupArchiveService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DISPLAY_DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final AtomicLong minioStorageUsedBytes = new AtomicLong(0L);
    private final AtomicLong minioStorageCapacityBytes = new AtomicLong(0L);
    private final AtomicLong minioStorageObjectCount = new AtomicLong(0L);

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    private boolean minioStorageGaugesRegistered = false;

    public MinioBackupArchiveService(@Nullable MinioClient minioClient, MinioProperties minioProperties) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
    }

    public AdminMinioStorageSummaryResponse getSummary() {
        if (!isConfigured()) {
            minioStorageUsedBytes.set(0L);
            minioStorageCapacityBytes.set(0L);
            minioStorageObjectCount.set(0L);
            return new AdminMinioStorageSummaryResponse(
                    false,
                    safeBucketName(),
                    0L,
                    0L,
                    "MinIO 설정이 준비되지 않았습니다."
            );
        }

        registerMinioStorageGauges();
        minioStorageCapacityBytes.set(Math.max(minioProperties.getStorageCapacityBytes(), 0L));

        try {
            MinioObjectSummary summary = scanObjects();
            minioStorageUsedBytes.set(summary.totalSizeBytes());
            minioStorageObjectCount.set(summary.objectCount());
            return new AdminMinioStorageSummaryResponse(
                    true,
                    safeBucketName(),
                    summary.objectCount(),
                    summary.totalSizeBytes(),
                    null
            );
        } catch (Exception exception) {
            return new AdminMinioStorageSummaryResponse(
                    false,
                    safeBucketName(),
                    0L,
                    0L,
                    "MinIO 저장소 상태를 불러오지 못했습니다."
            );
        }
    }

    public void writeBackupArchive(Path outputFile) {
        if (!isConfigured()) {
            throw new BadRequestException("MinIO 백업 설정이 준비되지 않았습니다.");
        }

        try (ZipOutputStream zipOutputStream = new ZipOutputStream(java.nio.file.Files.newOutputStream(outputFile))) {
            writeManifest(zipOutputStream);

            for (Result<Item> result : minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(minioProperties.getBucket_cloud())
                            .recursive(true)
                            .build()
            )) {
                Item item = result.get();
                if (item == null || item.isDir()) {
                    continue;
                }

                String objectName = item.objectName();
                String entryName = "objects/" + sanitizeEntryName(objectName);
                zipOutputStream.putNextEntry(new ZipEntry(entryName));
                try (InputStream inputStream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(minioProperties.getBucket_cloud())
                                .object(objectName)
                                .build()
                )) {
                    inputStream.transferTo(zipOutputStream);
                }
                zipOutputStream.closeEntry();
            }
        } catch (BadRequestException exception) {
            throw exception;
        } catch (UncheckedIOException exception) {
            throw new BadRequestException("MinIO 백업 파일을 생성하지 못했습니다.");
        } catch (Exception exception) {
            throw new BadRequestException("MinIO 백업을 생성하지 못했습니다.");
        }
    }

    private void writeManifest(ZipOutputStream zipOutputStream) {
        String manifest = """
                {
                  "type": "minio-backup",
                  "bucket": "%s",
                  "createdAt": "%s"
                }
                """.formatted(
                safeBucketName(),
                DISPLAY_DATE_TIME_FORMATTER.format(LocalDateTime.now(KST))
        );

        try {
            zipOutputStream.putNextEntry(new ZipEntry("_backup-manifest.json"));
            zipOutputStream.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void registerMinioStorageGauges() {
        if (meterRegistry == null || minioStorageGaugesRegistered) {
            return;
        }
        String bucket = safeBucketName();
        Gauge.builder("calen.minio.storage.used.bytes", minioStorageUsedBytes, AtomicLong::get)
                .description("Scanned MinIO object storage usage in bytes")
                .tag("bucket", bucket)
                .register(meterRegistry);
        Gauge.builder("calen.minio.storage.capacity.bytes", minioStorageCapacityBytes, AtomicLong::get)
                .description("Configured MinIO object storage capacity in bytes")
                .tag("bucket", bucket)
                .register(meterRegistry);
        Gauge.builder("calen.minio.storage.objects", minioStorageObjectCount, AtomicLong::get)
                .description("Scanned MinIO object count")
                .tag("bucket", bucket)
                .register(meterRegistry);
        minioStorageGaugesRegistered = true;
    }

    private MinioObjectSummary scanObjects() throws Exception {
        long objectCount = 0L;
        long totalSizeBytes = 0L;

        for (Result<Item> result : minioClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(minioProperties.getBucket_cloud())
                        .recursive(true)
                        .build()
        )) {
            Item item = result.get();
            if (item == null || item.isDir()) {
                continue;
            }
            objectCount += 1;
            totalSizeBytes += Math.max(item.size(), 0L);
        }

        return new MinioObjectSummary(objectCount, totalSizeBytes);
    }

    private boolean isConfigured() {
        return minioClient != null
                && minioProperties.getBucket_cloud() != null
                && !minioProperties.getBucket_cloud().isBlank();
    }

    private String safeBucketName() {
        return Objects.requireNonNullElse(minioProperties.getBucket_cloud(), "-");
    }

    private String sanitizeEntryName(String objectName) {
        String normalized = Objects.requireNonNullElse(objectName, "unnamed-object")
                .replace('\\', '/')
                .replaceAll("^/+", "");

        if (normalized.isBlank()) {
            return "unnamed-object";
        }

        Path path = Path.of(normalized).normalize();
        String sanitized = path.toString().replace('\\', '/');
        if (sanitized.startsWith("..")) {
            throw new BadRequestException("안전하지 않은 MinIO 객체 경로가 포함되어 있습니다.");
        }

        return sanitized;
    }

    private record MinioObjectSummary(long objectCount, long totalSizeBytes) {
    }
}
