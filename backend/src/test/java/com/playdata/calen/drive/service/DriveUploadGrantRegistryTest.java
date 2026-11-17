package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.storage.SignedUploadWindow;
import com.playdata.calen.drive.domain.DriveUploadGrant;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveUploadGrantRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DriveUploadGrantRegistryTest {
    @Test void recordsOnlyLocationAndSignedExpiration() {
        var grants = mock(DriveUploadGrantRepository.class);
        var registry = new DriveUploadGrantRegistry(grants);
        String url = "https://store.example/bucket/drive/1/a.png?X-Amz-Date=20261103T010203Z&X-Amz-Expires=600&X-Amz-Signature=PRIVATE";
        var response = response("drive/1/a.png", url, 600);
        registry.recordIssued(1L, "bucket", List.of(response));
        var captured = ArgumentCaptor.forClass(DriveUploadGrant.class);
        verify(grants).save(captured.capture());
        var grant = captured.getValue();
        assertThat(grant.getOwnerId()).isEqualTo(1L);
        assertThat(grant.getObjectKey()).isEqualTo("drive/1/a.png");
        assertThat(grant.getStorageBucket()).isEqualTo("bucket");
        assertThat(grant.getSignedAtUtc()).isEqualTo(LocalDateTime.of(2026, 11, 3, 1, 2, 3));
        assertThat(grant.getExpiresAtUtc()).isEqualTo(LocalDateTime.of(2026, 11, 3, 1, 12, 3));
        assertThat(grant.toString()).doesNotContain("PRIVATE", url);
    }

    @Test void readsTheSignatureWindowProducedByTheActualMinioSdkWithoutNetwork() throws Exception {
        MinioClient client = MinioClient.builder().endpoint("http://127.0.0.1:9000")
                .credentials("local-test-access", "local-test-secret").region("us-east-1").build();
        String url = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.PUT)
                .bucket("test-bucket").object("drive/1/test.png").expiry(600).build());
        var window = SignedUploadWindow.parse(url, 600);
        assertThat(Duration.between(window.signedAtUtc(), window.expiresAtUtc())).isEqualTo(Duration.ofSeconds(600));
    }

    @Test void rejectsMissingAmbiguousOrInvalidExpiryWithoutLeakingTheUrl() {
        for (String url : List.of(
                "https://example.test/file?X-Amz-Signature=PRIVATE",
                "https://example.test/file?X-Amz-Date=20261103T010203Z&X-Amz-Expires=600&X-Amz-Expires=1&secret=PRIVATE",
                "https://example.test/file?X-Amz-Date=20260230T010203Z&X-Amz-Expires=600&secret=PRIVATE",
                "https://example.test/file?X-Amz-Date=20261103T010203Z&X-Amz-Expires=700&secret=PRIVATE")) {
            assertThatThrownBy(() -> SignedUploadWindow.parse(url, 600))
                    .isInstanceOf(ServiceUnavailableException.class).hasMessageNotContaining("PRIVATE");
        }
    }

    @Test void rejectsForeignOrNestedObjectKeysBeforeSaving() {
        var grants = mock(DriveUploadGrantRepository.class);
        var registry = new DriveUploadGrantRegistry(grants);
        String url = "https://example.test/file?X-Amz-Date=20261103T010203Z&X-Amz-Expires=600";
        for (String key : List.of("drive/2/a.png", "drive/1/../a.png", "drive/1/\\a.png")) {
            assertThatThrownBy(() -> registry.recordIssued(1L, "bucket", List.of(response(key, url, 600))))
                    .isInstanceOf(ServiceUnavailableException.class);
        }
        verifyNoInteractions(grants);
    }

    private DriveDtos.UploadChunkResponse response(String key, String url, int expiry) {
        return DriveDtos.UploadChunkResponse.builder().objectKey(key).finalObjectKey(key)
                .presignedUploadUrl(url).presignedUrlExpiresIn(expiry).partitioned(false).build();
    }
}
