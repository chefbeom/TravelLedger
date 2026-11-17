package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequest;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequestRepository;
import com.playdata.calen.account.withdrawal.DriveShareWithdrawalCleanup;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveShare;
import com.playdata.calen.drive.repository.DriveShareRepository;
import jakarta.persistence.EntityManager;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.account-withdrawal.internal-workflow-enabled=true",
        "spring.datasource.url=jdbc:h2:mem:drive-share-withdrawal-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class DriveShareWithdrawalConcurrencyTest {
    @Autowired AppUserRepository users;
    @Autowired DriveShareRepository shares;
    @Autowired DriveShareService driveShares;
    @Autowired DriveShareWithdrawalCleanup cleanup;
    @Autowired AccountWithdrawalRequestRepository requests;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entities;

    @Test void aNewShareCannotBeCreatedAfterItsRecipientBeginsWithdrawal() throws Exception {
        Fixture f = fixture(false);
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var withdrawal = pool.submit(() -> tx(() -> {
                users.lockForAccountSecurityUpdate(f.recipientId()).orElseThrow().setActive(false);
                request(f.recipientId());
                entities.flush(); locked.countDown(); await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var create = pool.submit(() -> driveShares.shareFiles(f.ownerId(), List.of(f.itemId()), f.recipientLogin(), "view"));
            assertThatThrownBy(() -> create.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); withdrawal.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> create.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
            assertThat(shares.findByItem_IdAndRecipient_Id(f.itemId(), f.recipientId())).isEmpty();
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void aSharedFileCannotBeOpenedAfterItsOwnerBeginsWithdrawal() throws Exception {
        Fixture f = fixture(true);
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var withdrawal = pool.submit(() -> tx(() -> {
                users.lockForAccountSecurityUpdate(f.ownerId()).orElseThrow().setActive(false);
                request(f.ownerId());
                entities.flush(); locked.countDown(); await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var download = pool.submit(() -> driveShares.downloadSharedFile(f.recipientId(), f.itemId()));
            assertThatThrownBy(() -> download.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); withdrawal.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> download.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void anOrdinarilyDisabledOwnerRetainsLegacySharedFileAccess() {
        Fixture f = fixture(true);
        tx(() -> users.lockForAccountSecurityUpdate(f.ownerId()).orElseThrow().setActive(false));
        assertThat(driveShares.downloadSharedFile(f.recipientId(), f.itemId()).fileName()).isEqualTo("photo.txt");
    }

    @Test void aRemovedShareCannotBeUsedByARequestThatWasAlreadyWaiting() throws Exception {
        Fixture f = fixture(true);
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var revoke = pool.submit(() -> tx(() -> {
                users.lockForAccountSecurityUpdate(f.ownerId()).orElseThrow();
                shares.deleteById(f.shareId());
                entities.flush(); locked.countDown(); await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var download = pool.submit(() -> driveShares.downloadSharedFile(f.recipientId(), f.itemId()));
            assertThatThrownBy(() -> download.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); revoke.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> download.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void unrelatedShareCreatedDuringWithdrawalIsPreservedAndMarkedShared() throws Exception {
        Fixture f = fixture(true);
        Recipient other = new TransactionTemplate(transactions).execute(status -> {
            AppUser user = user();
            entities.flush();
            return new Recipient(user.getId(), user.getLoginId());
        });
        var pool = Executors.newFixedThreadPool(2);
        var removed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var withdrawal = pool.submit(() -> tx(() -> {
                users.lockForAccountSecurityUpdate(f.recipientId()).orElseThrow().setActive(false);
                UUID requestId = request(f.recipientId());
                cleanup.removeDirectShares(f.recipientId(), requestId);
                entities.flush(); removed.countDown(); await(release);
            }));
            assertThat(removed.await(5, TimeUnit.SECONDS)).isTrue();
            var share = pool.submit(() -> driveShares.shareFiles(f.ownerId(), List.of(f.itemId()), other.loginId(), "view"));
            assertThatThrownBy(() -> share.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); withdrawal.get(5, TimeUnit.SECONDS); share.get(5, TimeUnit.SECONDS);
            assertThat(shares.findByItem_IdAndRecipient_Id(f.itemId(), f.recipientId())).isEmpty();
            assertThat(shares.findByItem_IdAndRecipient_Id(f.itemId(), other.id())).isPresent();
            new TransactionTemplate(transactions).executeWithoutResult(status ->
                    assertThat(entities.find(DriveItem.class, f.itemId()).isSharedFile()).isTrue());
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    private Fixture fixture(boolean withShare) {
        return new TransactionTemplate(transactions).execute(status -> {
            AppUser owner = user(), recipient = user();
            DriveItem item = new DriveItem(); item.setOwner(owner); item.setOriginalName("photo.txt");
            item.setStoredName("photo.txt"); item.setExtension("txt"); item.setStoragePath("test/" + UUID.randomUUID());
            entities.persist(item);
            DriveShare share = null;
            if (withShare) {
                share = new DriveShare(); share.setOwner(owner); share.setRecipient(recipient); share.setItem(item);
                entities.persist(share);
            }
            entities.flush();
            return new Fixture(owner.getId(), recipient.getId(), recipient.getLoginId(), item.getId(), share == null ? null : share.getId());
        });
    }

    private AppUser user() {
        AppUser user = new AppUser(); user.setLoginId("drive-share-" + UUID.randomUUID()); user.setDisplayName("사용자");
        entities.persist(user); return user;
    }

    private UUID request(Long userId) {
        UUID id = UUID.randomUUID();
        var request = new AccountWithdrawalRequest(); request.setId(id.toString()); request.setUserId(userId);
        request.setState(AccountWithdrawalRequest.State.IN_PROGRESS); request.setRequestedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now()); requests.saveAndFlush(request);
        return id;
    }

    private void tx(Runnable work) { new TransactionTemplate(transactions).executeWithoutResult(status -> work.run()); }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
        }
    }
    private record Fixture(Long ownerId, Long recipientId, String recipientLogin, Long itemId, Long shareId) {}
    private record Recipient(Long id, String loginId) {}
}
