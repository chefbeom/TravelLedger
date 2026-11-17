package com.playdata.calen.familyalbum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.familyalbum.domain.FamilyCategory;
import com.playdata.calen.familyalbum.domain.FamilyCategoryMember;
import com.playdata.calen.familyalbum.domain.FamilyMediaAsset;
import com.playdata.calen.familyalbum.domain.FamilyMediaType;
import com.playdata.calen.familyalbum.dto.FamilyMediaUploadAbortRequest;
import com.playdata.calen.familyalbum.dto.FamilyMediaUploadCompleteFileRequest;
import com.playdata.calen.familyalbum.dto.FamilyMediaUploadCompleteRequest;
import com.playdata.calen.familyalbum.repository.FamilyCategoryMemberRepository;
import com.playdata.calen.familyalbum.repository.FamilyCategoryRepository;
import com.playdata.calen.familyalbum.repository.FamilyMediaAssetRepository;
import com.playdata.calen.familyalbum.service.FamilyAlbumService;
import com.playdata.calen.familyalbum.service.FamilyAlbumWithdrawalService;
import com.playdata.calen.familyalbum.service.FamilyMediaStorageService;
import java.util.List;
import java.util.Map;
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

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:family-abort-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class FamilyMediaAbortSafetyTest {
    @Autowired AppUserRepository users;
    @Autowired FamilyCategoryRepository categories;
    @Autowired FamilyCategoryMemberRepository members;
    @Autowired FamilyMediaAssetRepository media;
    @Autowired FamilyAlbumService family;
    @Autowired FamilyAlbumWithdrawalService transfers;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean FamilyMediaStorageService storage;

    @Test void alreadyRegisteredOrTransferredPhotoCannotBeRemovedByAbortingUpload() {
        AppUser owner = user();
        AppUser successor = user();
        FamilyCategory category = category(owner);
        addMember(category, owner);
        addMember(category, successor);
        String objectKey = key(owner, category);
        FamilyMediaAsset photo = photo(category, owner, objectKey);

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            users.findActiveForPreferenceUpdate(owner.getId()).orElseThrow();
            transfers.transferForWithdrawal(owner.getId(), Map.of(category.getId(), successor.getId()));
        });
        assertThat(media.findById(photo.getId()).orElseThrow().getOwner().getId()).isEqualTo(successor.getId());
        assertThatThrownBy(() -> family.abortMediaUpload(owner.getId(), category.getId(),
                new FamilyMediaUploadAbortRequest(List.of("pending-upload", objectKey))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("이미 등록된");
        verify(storage, never()).abortPresignedUploads(anyLong(), anyLong(), anyList());
        assertThat(media.findById(photo.getId())).isPresent();
    }

    @Test void anUnregisteredUploadCanStillBeAborted() {
        AppUser owner = user();
        FamilyCategory category = category(owner);
        String objectKey = key(owner, category);
        family.abortMediaUpload(owner.getId(), category.getId(), new FamilyMediaUploadAbortRequest(List.of(objectKey)));
        verify(storage).abortPresignedUploads(owner.getId(), category.getId(), List.of(objectKey));
    }

    @Test void aDeactivatedUploaderCannotAbortAFormerUpload() {
        AppUser owner = user();
        FamilyCategory category = category(owner);
        String objectKey = key(owner, category);
        owner.setActive(false);
        users.saveAndFlush(owner);
        assertThatThrownBy(() -> family.abortMediaUpload(owner.getId(), category.getId(),
                new FamilyMediaUploadAbortRequest(List.of(objectKey)))).isInstanceOf(NotFoundException.class);
        verify(storage, never()).abortPresignedUploads(anyLong(), anyLong(), anyList());
    }

    @Test void abortWaitsForConcurrentCompletionAndCannotDeleteTheConfirmedPhoto() throws Exception {
        AppUser owner = user();
        FamilyCategory category = category(owner);
        String objectKey = key(owner, category);
        var insideCompletion = new CountDownLatch(1);
        var allowCompletion = new CountDownLatch(1);
        when(storage.completePresignedUpload(eq(owner.getId()), eq(category.getId()), any())).thenAnswer(call -> {
            insideCompletion.countDown();
            if (!allowCompletion.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("completion timed out");
            return new FamilyMediaStorageService.StoredFamilyMedia("photo.jpg", "photo.jpg", objectKey,
                    "image/jpeg", 1L, FamilyMediaType.PHOTO);
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var complete = pool.submit(() -> family.completeMediaUpload(owner.getId(), category.getId(),
                    new FamilyMediaUploadCompleteRequest(null, List.of(
                            new FamilyMediaUploadCompleteFileRequest(objectKey, "photo.jpg", "image/jpeg", 1L)))));
            assertThat(insideCompletion.await(5, TimeUnit.SECONDS)).isTrue();
            var abort = pool.submit(() -> family.abortMediaUpload(owner.getId(), category.getId(),
                    new FamilyMediaUploadAbortRequest(List.of(objectKey))));
            assertThatThrownBy(() -> abort.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            allowCompletion.countDown();
            assertThat(complete.get(5, TimeUnit.SECONDS)).hasSize(1);
            assertThatThrownBy(() -> abort.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BadRequestException.class);
            verify(storage, never()).abortPresignedUploads(anyLong(), anyLong(), anyList());
            assertThat(media.existsByStoragePathIn(List.of(objectKey))).isTrue();
        } finally {
            allowCompletion.countDown();
            pool.shutdownNow();
        }
    }

    private AppUser user() {
        AppUser value = new AppUser();
        value.setLoginId("family_abort_" + UUID.randomUUID());
        value.setDisplayName("테스트 회원");
        return users.saveAndFlush(value);
    }

    private FamilyCategory category(AppUser owner) {
        FamilyCategory value = new FamilyCategory();
        value.setOwner(owner);
        value.setName("가족 앨범");
        return categories.saveAndFlush(value);
    }

    private void addMember(FamilyCategory category, AppUser user) {
        FamilyCategoryMember membership = new FamilyCategoryMember();
        membership.setCategory(category);
        membership.setUser(user);
        members.saveAndFlush(membership);
    }

    private String key(AppUser owner, FamilyCategory category) {
        return "family/" + owner.getId() + "/" + category.getId() + "/photo.jpg";
    }

    private FamilyMediaAsset photo(FamilyCategory category, AppUser owner, String objectKey) {
        FamilyMediaAsset asset = new FamilyMediaAsset();
        asset.setCategory(category);
        asset.setOwner(owner);
        asset.setOriginalFileName("photo.jpg");
        asset.setStoredFileName("photo.jpg");
        asset.setStoragePath(objectKey);
        asset.setContentType("image/jpeg");
        asset.setFileSize(1L);
        return media.saveAndFlush(asset);
    }
}
