package com.playdata.calen.familyalbum.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.familyalbum.domain.FamilyMediaUploadGrant;
import com.playdata.calen.familyalbum.dto.FamilyMediaUploadTargetResponse;
import com.playdata.calen.familyalbum.repository.FamilyMediaUploadGrantRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class FamilyMediaUploadGrantRegistryTest {
    @Test void recordsTheUploaderCategoryBucketAndSignedExpiryWithoutTheUrl() {
        var grants = mock(FamilyMediaUploadGrantRepository.class);
        var registry = registry(grants);
        String key = "family-media/7/9/file.png";
        registry.recordIssued(7L, 9L, List.of(target(key, signedUrl())));
        var captured = ArgumentCaptor.forClass(FamilyMediaUploadGrant.class);
        verify(grants).save(captured.capture());
        var grant = captured.getValue();
        assertThat(grant.getOwnerId()).isEqualTo(7L);
        assertThat(grant.getCategoryId()).isEqualTo(9L);
        assertThat(grant.getObjectKey()).isEqualTo(key);
        assertThat(grant.getStorageBucket()).isEqualTo("family-bucket");
        assertThat(grant.getExpiresAtUtc()).isEqualTo(LocalDateTime.of(2026, 11, 3, 1, 12, 3));
        assertThat(grant.toString()).doesNotContain("PRIVATE");
    }

    @Test void rejectsOtherOwnersCategoriesAndNestedKeys() {
        var grants = mock(FamilyMediaUploadGrantRepository.class);
        var registry = registry(grants);
        for (String key : List.of("family-media/8/9/file.png", "family-media/7/10/file.png", "family-media/7/9/../file.png")) {
            assertThatThrownBy(() -> registry.recordIssued(7L, 9L, List.of(target(key, signedUrl()))))
                    .isInstanceOf(ServiceUnavailableException.class).hasMessageNotContaining("PRIVATE");
        }
        verifyNoInteractions(grants);
    }

    private FamilyMediaUploadGrantRegistry registry(FamilyMediaUploadGrantRepository grants) {
        var minio = new MinioProperties(); minio.setBucket_cloud("family-bucket"); minio.setPresignedUrlExpirySeconds(600);
        var registry = new FamilyMediaUploadGrantRegistry(grants, minio);
        ReflectionTestUtils.setField(registry, "configuredPrefix", "family-media");
        return registry;
    }
    private String signedUrl() { return "https://store.example/a?X-Amz-Date=20261103T010203Z&X-Amz-Expires=600&X-Amz-Signature=PRIVATE"; }
    private FamilyMediaUploadTargetResponse target(String key, String url) {
        return new FamilyMediaUploadTargetResponse("PUT", url, key, "file.png", "file.png", "image/png", 1L, "PHOTO");
    }
}
