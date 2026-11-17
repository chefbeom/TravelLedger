package com.playdata.calen.familyalbum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequest;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequestRepository;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.familyalbum.domain.FamilyCategory;
import com.playdata.calen.familyalbum.domain.FamilyCategoryMember;
import com.playdata.calen.familyalbum.domain.FamilyMediaAsset;
import com.playdata.calen.familyalbum.dto.FamilyAlbumCreateRequest;
import com.playdata.calen.familyalbum.dto.FamilyCategoryCreateRequest;
import com.playdata.calen.familyalbum.repository.FamilyCategoryMemberRepository;
import com.playdata.calen.familyalbum.repository.FamilyCategoryRepository;
import com.playdata.calen.familyalbum.repository.FamilyMediaAssetRepository;
import com.playdata.calen.familyalbum.service.FamilyAlbumWithdrawalService;
import com.playdata.calen.familyalbum.service.FamilyAlbumService;
import java.util.Map;
import java.util.List;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:family-transfer-concurrency-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class FamilyAlbumWithdrawalConcurrencyTest {
    @Autowired AppUserRepository users;
    @Autowired FamilyCategoryRepository categories;
    @Autowired FamilyCategoryMemberRepository members;
    @Autowired FamilyMediaAssetRepository media;
    @Autowired AccountWithdrawalRequestRepository withdrawals;
    @Autowired FamilyAlbumWithdrawalService transfers;
    @Autowired FamilyAlbumService family;
    @Autowired PlatformTransactionManager transactions;

    @Test void selectedMembershipCannotDisappearWhileTransferIsInProgress() throws Exception {
        var fixture = new TransactionTemplate(transactions).execute(status -> {
            AppUser owner = user();
            AppUser member = user();
            FamilyCategory category = new FamilyCategory();
            category.setOwner(owner);
            category.setName("가족 추억");
            categories.saveAndFlush(category);
            FamilyCategoryMember membership = new FamilyCategoryMember();
            membership.setCategory(category);
            membership.setUser(member);
            members.saveAndFlush(membership);
            return new Fixture(owner.getId(), member.getId(), category.getId(), membership.getId());
        });

        var pool = Executors.newFixedThreadPool(2);
        var transferLocked = new CountDownLatch(1);
        var releaseTransfer = new CountDownLatch(1);
        var deletionStarted = new CountDownLatch(1);
        try {
            var transfer = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                users.findActiveForPreferenceUpdate(fixture.ownerId()).orElseThrow();
                transfers.transferForWithdrawal(fixture.ownerId(), Map.of(fixture.categoryId(), fixture.memberId()));
                transferLocked.countDown();
                try {
                    if (!releaseTransfer.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("transfer timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(transferLocked.await(5, TimeUnit.SECONDS)).isTrue();
            var deletion = pool.submit(() -> {
                deletionStarted.countDown();
                members.deleteById(fixture.membershipId());
            });
            assertThat(deletionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> deletion.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseTransfer.countDown();
            transfer.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);
            assertThat(categories.findById(fixture.categoryId()).orElseThrow().getOwner().getId()).isEqualTo(fixture.memberId());
        } finally {
            releaseTransfer.countDown();
            pool.shutdownNow();
        }
    }

    @Test void familyUploadWaitingForDeactivationCannotAddMediaAfterward() throws Exception {
        AppUser owner = user();
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AppUser current = users.lockForAccountSecurityUpdate(owner.getId()).orElseThrow();
                current.setActive(false); users.saveAndFlush(current); locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("deactivation timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var upload = pool.submit(() -> family.uploadMedia(owner.getId(), 123L, null, java.util.List.of()));
            assertThatThrownBy(() -> upload.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); deactivate.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> upload.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void categoryInvitationWaitingForDeactivationCannotAddAnInactiveMember() throws Exception {
        AppUser creator = user(), invitee = user();
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AppUser current = users.lockForAccountSecurityUpdate(invitee.getId()).orElseThrow();
                current.setActive(false); users.saveAndFlush(current); locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("deactivation timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var create = pool.submit(() -> family.createCategory(creator.getId(),
                    new FamilyCategoryCreateRequest("가족 추억", null, List.of(invitee.getId()))));
            assertThatThrownBy(() -> create.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); deactivate.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> create.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BadRequestException.class);
            assertThat(categories.findAllByOwnerIdOrderByIdAsc(creator.getId())).isEmpty();
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void activeInvitesKeepTheirExistingOrderAndIgnoreDuplicates() {
        AppUser creator = user(), invitee = user();
        var result = family.createCategory(creator.getId(),
                new FamilyCategoryCreateRequest("가족 추억", null, List.of(invitee.getId(), creator.getId(), invitee.getId())));
        assertThat(result.memberCount()).isEqualTo(2);
        assertThat(result.members()).extracting("userId").containsExactly(creator.getId(), invitee.getId());
        assertThat(members.existsByCategoryIdAndUserId(result.id(), invitee.getId())).isTrue();
    }

    @Test void simultaneousMutualInvitationsAcquireAccountsInTheSameOrder() throws Exception {
        AppUser first = user(), second = user();
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try {
            var left = pool.submit(() -> {
                ready.countDown(); release.await();
                return family.createCategory(first.getId(), new FamilyCategoryCreateRequest("첫 가족", null, List.of(second.getId())));
            });
            var right = pool.submit(() -> {
                ready.countDown(); release.await();
                return family.createCategory(second.getId(), new FamilyCategoryCreateRequest("둘째 가족", null, List.of(first.getId())));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(left.get(10, TimeUnit.SECONDS).memberCount()).isEqualTo(2);
            assertThat(right.get(10, TimeUnit.SECONDS).memberCount()).isEqualTo(2);
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void ordinaryDisabledOwnersMediaCanStillBeAddedWhenThereIsNoWithdrawal() {
        AppUser creator = user(), disabled = user();
        var category = new FamilyCategory(); category.setOwner(creator); category.setName("기존 공유");
        categories.saveAndFlush(category);
        var photo = new FamilyMediaAsset(); photo.setCategory(category); photo.setOwner(disabled);
        photo.setOriginalFileName("photo.jpg"); photo.setStoredFileName("photo.jpg"); photo.setStoragePath("fixture/photo.jpg");
        photo.setContentType("image/jpeg"); photo.setFileSize(1L); media.saveAndFlush(photo);
        disabled.setActive(false); users.saveAndFlush(disabled);
        var album = family.createAlbum(creator.getId(),
                new FamilyAlbumCreateRequest(category.getId(), "기존 앨범", null, List.of(photo.getId())));
        assertThat(album.id()).isNotNull();
    }

    @Test void aPendingWithdrawalPreventsAddingItsRemainingMediaToANewAlbum() {
        AppUser creator = user(), withdrawing = user();
        var category = new FamilyCategory(); category.setOwner(creator); category.setName("기존 공유");
        categories.saveAndFlush(category);
        var photo = new FamilyMediaAsset(); photo.setCategory(category); photo.setOwner(withdrawing);
        photo.setOriginalFileName("photo.jpg"); photo.setStoredFileName("photo.jpg"); photo.setStoragePath("fixture/photo.jpg");
        photo.setContentType("image/jpeg"); photo.setFileSize(1L); media.saveAndFlush(photo);
        withdrawing.setActive(false); users.saveAndFlush(withdrawing);
        var request = new AccountWithdrawalRequest(); request.setId(UUID.randomUUID().toString());
        request.setUserId(withdrawing.getId()); request.setState(AccountWithdrawalRequest.State.IN_PROGRESS);
        request.setRequestedAt(LocalDateTime.now()); request.setUpdatedAt(LocalDateTime.now()); withdrawals.saveAndFlush(request);
        assertThatThrownBy(() -> family.createAlbum(creator.getId(),
                new FamilyAlbumCreateRequest(category.getId(), "새 앨범", null, List.of(photo.getId()))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("탈퇴 처리 중");
        assertThat(media.findById(photo.getId())).isPresent();
    }

    private AppUser user() {
        AppUser value = new AppUser();
        value.setLoginId("family_" + UUID.randomUUID());
        value.setDisplayName("테스트 회원");
        return users.saveAndFlush(value);
    }

    private record Fixture(Long ownerId, Long memberId, Long categoryId, Long membershipId) {}
}
