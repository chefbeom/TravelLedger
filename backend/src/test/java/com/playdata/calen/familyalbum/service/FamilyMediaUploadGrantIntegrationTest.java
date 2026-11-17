package com.playdata.calen.familyalbum.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.familyalbum.domain.FamilyCategory;
import com.playdata.calen.familyalbum.dto.FamilyMediaUploadFileRequest;
import com.playdata.calen.familyalbum.dto.FamilyMediaUploadPrepareRequest;
import com.playdata.calen.familyalbum.repository.FamilyCategoryRepository;
import com.playdata.calen.familyalbum.repository.FamilyMediaUploadGrantRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.playdata.calen.common.exception.NotFoundException;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "MINIO_CLOUD_BUCKET=family-test", "MINIO_PRESIGNED_URL_EXPIRY_SECONDS=600",
        "spring.datasource.url=jdbc:h2:mem:family-grant-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class FamilyMediaUploadGrantIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired FamilyCategoryRepository categories;
    @Autowired FamilyMediaUploadGrantRepository grants;
    @Autowired FamilyAlbumService albums;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean FamilyMediaStorageService storage;

    @Test void issuanceCommitsTrackingBeforeReturningTheUnchangedUrl() {
        AppUser owner = user(); FamilyCategory category = category(owner);
        String key = "family-media/" + owner.getId() + "/" + category.getId() + "/file.png";
        String url = "https://example.test/file?X-Amz-Date=20261103T010203Z&X-Amz-Expires=600&X-Amz-Signature=PRIVATE";
        when(storage.preparePresignedUploads(eq(owner.getId()), eq(category.getId()), anyList())).thenReturn(List.of(target(key, url)));
        var response = albums.prepareMediaUpload(owner.getId(), category.getId(), request());
        assertThat(response.uploads()).singleElement().satisfies(upload -> assertThat(upload.uploadUrl()).isEqualTo(url));
        assertThat(grants.findAllByOwnerIdOrderByIdAsc(owner.getId())).singleElement().satisfies(grant -> {
            assertThat(grant.getObjectKey()).isEqualTo(key);
            assertThat(grant.getStorageBucket()).isEqualTo("family-test");
            assertThat(grant.getCategoryId()).isEqualTo(category.getId());
            assertThat(grant.getExpiresAtUtc()).isEqualTo(LocalDateTime.of(2026, 11, 3, 1, 12, 3));
        });
    }

    @Test void malformedLaterUrlRollsBackEarlierGrantAndDoesNotReturnUrls() {
        AppUser owner = user(); FamilyCategory category = category(owner);
        String prefix = "family-media/" + owner.getId() + "/" + category.getId() + "/";
        var good = target(prefix + "good.png", "https://example.test/file?X-Amz-Date=20261103T010203Z&X-Amz-Expires=600");
        var bad = target(prefix + "bad.png", "https://example.test/file?secret=PRIVATE");
        when(storage.preparePresignedUploads(eq(owner.getId()), eq(category.getId()), anyList())).thenReturn(List.of(good, bad));
        assertThatThrownBy(() -> albums.prepareMediaUpload(owner.getId(), category.getId(), request()))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageNotContaining("PRIVATE");
        assertThat(grants.findAllByOwnerIdOrderByIdAsc(owner.getId())).isEmpty();
    }

    @Test void issuanceWaitingForDeactivationCannotProduceAnUntrackedUrl() throws Exception {
        AppUser owner = user(); FamilyCategory category = category(owner);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                users.lockForAccountSecurityUpdate(owner.getId()).orElseThrow().setActive(false);
                users.flush(); locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var issue = pool.submit(() -> albums.prepareMediaUpload(owner.getId(), category.getId(), request()));
            assertThatThrownBy(() -> issue.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); deactivate.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> issue.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
            verify(storage, never()).preparePresignedUploads(eq(owner.getId()), eq(category.getId()), anyList());
            assertThat(grants.findAllByOwnerIdOrderByIdAsc(owner.getId())).isEmpty();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private AppUser user() {
        var user = new AppUser(); user.setLoginId("family-grant-" + UUID.randomUUID()); user.setDisplayName("Owner");
        return users.saveAndFlush(user);
    }
    private FamilyCategory category(AppUser owner) {
        var value = new FamilyCategory(); value.setOwner(owner); value.setName("Family"); return categories.saveAndFlush(value);
    }
    private FamilyMediaUploadPrepareRequest request() {
        return new FamilyMediaUploadPrepareRequest(List.of(new FamilyMediaUploadFileRequest("file.png", "image/png", 1)));
    }
    private FamilyMediaStorageService.PresignedFamilyMediaUpload target(String key, String url) {
        return new FamilyMediaStorageService.PresignedFamilyMediaUpload("PUT", url, key, "file.png", "file.png", "image/png", 1L, "PHOTO");
    }
}
