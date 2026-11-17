package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveUploadGrantRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:drive-grant-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class DriveUploadGrantIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired DriveUploadGrantRepository grants;
    @Autowired DriveService drive;
    @MockitoBean DriveStorageService storage;

    @Test void issuanceCommitsTheExpiryWithoutChangingTheUrlReturnedToTheClient() {
        AppUser owner = user();
        String key = "drive/" + owner.getId() + "/" + UUID.randomUUID() + ".png";
        String signedUrl = "https://example.test/object?X-Amz-Date=20261103T010203Z&X-Amz-Expires=600&X-Amz-Signature=PRIVATE";
        var response = response(key, signedUrl);
        when(storage.initUpload(eq(owner.getId()), anyList())).thenReturn(List.of(response));
        when(storage.resolveBucket()).thenReturn("drive-bucket");
        var requests = List.of(DriveDtos.UploadInitRequest.builder().fileOriginName("a.png").build());
        assertThat(drive.initializeUpload(owner.getId(), requests)).containsExactly(response);
        var saved = grants.findAllByOwnerIdOrderByIdAsc(owner.getId());
        assertThat(saved).singleElement().satisfies(grant -> {
            assertThat(grant.getObjectKey()).isEqualTo(key);
            assertThat(grant.getStorageBucket()).isEqualTo("drive-bucket");
            assertThat(grant.getExpiresAtUtc()).isEqualTo(LocalDateTime.of(2026, 11, 3, 1, 12, 3));
        });
    }

    @Test void badGrantRollsBackEarlierGrantsAndDoesNotReturnAnyUrl() {
        AppUser owner = user();
        String key = "drive/" + owner.getId() + "/" + UUID.randomUUID() + ".png";
        var good = response(key, "https://example.test/a?X-Amz-Date=20261103T010203Z&X-Amz-Expires=600");
        var bad = response("drive/" + owner.getId() + "/other.png", "https://example.test/b?secret=PRIVATE");
        when(storage.initUpload(eq(owner.getId()), anyList())).thenReturn(List.of(good, bad));
        when(storage.resolveBucket()).thenReturn("drive-bucket");
        assertThatThrownBy(() -> drive.initializeUpload(owner.getId(),
                List.of(DriveDtos.UploadInitRequest.builder().fileOriginName("a.png").build())))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageNotContaining("PRIVATE");
        assertThat(grants.findAllByOwnerIdOrderByIdAsc(owner.getId())).isEmpty();
    }

    private AppUser user() {
        var user = new AppUser(); user.setLoginId("grant-" + UUID.randomUUID()); user.setDisplayName("Grant owner");
        return users.saveAndFlush(user);
    }

    private DriveDtos.UploadChunkResponse response(String key, String url) {
        return DriveDtos.UploadChunkResponse.builder().objectKey(key).finalObjectKey(key)
                .presignedUploadUrl(url).presignedUrlExpiresIn(600).partitioned(false).build();
    }
}
