package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveShare;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveItemRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
        "spring.datasource.url=jdbc:h2:mem:drive-upload-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class DriveUploadWithdrawalConcurrencyTest {
    @Autowired AppUserRepository users;
    @Autowired DriveItemRepository items;
    @Autowired DriveService drive;
    @Autowired DriveShareService shares;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entities;
    @MockitoBean DriveStorageService storage;

    @Test void uploadAndSharedCopyWaitingForDeactivationAreRejected() throws Exception {
        Long ownerId = owner();
        Long sharedItemId = sharedItemFor(ownerId);
        var pool = Executors.newFixedThreadPool(6);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                users.lockForAccountSecurityUpdate(ownerId).orElseThrow().setActive(false);
                users.flush();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            List<Callable<Object>> operations = List.of(
                    () -> drive.initializeUpload(ownerId, null),
                    () -> drive.completeUpload(ownerId, null),
                    () -> drive.abortUpload(ownerId, null),
                    () -> drive.createFolder(ownerId, null),
                    () -> shares.saveSharedFileToDrive(ownerId, sharedItemId, null));
            var writes = operations.stream().map(pool::submit).toList();
            for (var write : writes) {
                assertThatThrownBy(() -> write.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            }
            release.countDown();
            deactivate.get(5, TimeUnit.SECONDS);
            for (var write : writes) {
                assertThatThrownBy(() -> write.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(NotFoundException.class);
            }
            verify(storage, never()).initUpload(eq(ownerId), any());
            verify(storage, never()).completeUpload(any());
            verify(storage, never()).abortUpload(eq(ownerId), any());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void cancellationWaitsForCompletionAndCannotDeleteTheRegisteredObject() throws Exception {
        Long ownerId = owner();
        String key = "drive/" + ownerId + "/file.pdf";
        var upload = DriveDtos.UploadCompleteRequest.builder().fileOriginName("file.pdf").fileFormat("pdf")
                .fileSize(42L).finalObjectKey(key).build();
        var abort = DriveDtos.UploadAbortRequest.builder().finalObjectKey(key).build();
        var remoteStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(storage.completeUpload(upload)).thenAnswer(invocation -> {
            remoteStarted.countDown();
            await(release);
            return DriveDtos.UploadCompleteResponse.builder().fileOriginName("file.pdf").fileSaveName("file.pdf")
                    .fileFormat("pdf").finalObjectKey(key).build();
        });
        when(storage.resolveObjectSize(key)).thenReturn(42L);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var completion = pool.submit(() -> drive.completeUpload(ownerId, upload));
            assertThat(remoteStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var cancellation = pool.submit(() -> drive.abortUpload(ownerId, abort));
            assertThatThrownBy(() -> cancellation.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            completion.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> cancellation.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BadRequestException.class);
            assertThat(items.findByOwner_IdAndStoragePath(ownerId, key)).isPresent();
            verify(storage, never()).abortUpload(ownerId, abort);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void fileChangesAndDownloadsWaitingForDeactivationCannotRunAfterwards() throws Exception {
        Long ownerId = owner();
        DriveItem own = new DriveItem();
        own.setOwner(users.findById(ownerId).orElseThrow());
        own.setOriginalName("file.txt"); own.setStoredName("file.txt");
        own.setStoragePath("drive/" + ownerId + "/file.txt");
        Long fileId = items.saveAndFlush(own).getId();
        var pool = Executors.newFixedThreadPool(15);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                users.lockForAccountSecurityUpdate(ownerId).orElseThrow().setActive(false);
                users.flush(); locked.countDown(); await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            List<Callable<Object>> operations = List.of(
                    () -> drive.moveToTrash(ownerId, fileId),
                    () -> drive.restoreFromTrash(ownerId, fileId),
                    () -> drive.deletePermanently(ownerId, fileId),
                    () -> drive.clearTrash(ownerId),
                    () -> drive.moveToFolder(ownerId, fileId, null),
                    () -> drive.renameItem(ownerId, fileId, "renamed.txt"),
                    () -> drive.setItemLocked(ownerId, fileId, true),
                    () -> drive.moveItemsToFolder(ownerId, List.of(fileId), null),
                    () -> drive.restoreItemsFromTrash(ownerId, List.of(fileId)),
                    () -> drive.restoreFileVersion(ownerId, fileId, 1L),
                    () -> drive.downloadFile(ownerId, fileId),
                    () -> drive.downloadItemsAsZip(ownerId, List.of(fileId)),
                    () -> drive.getDownloadUrl(ownerId, fileId));
            var writes = operations.stream().map(pool::submit).toList();
            for (var write : writes) {
                assertThatThrownBy(() -> write.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            }
            release.countDown(); deactivate.get(5, TimeUnit.SECONDS);
            for (var write : writes) {
                assertThatThrownBy(() -> write.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(NotFoundException.class);
            }
            DriveItem retained = items.findById(fileId).orElseThrow();
            assertThat(retained.getOriginalName()).isEqualTo("file.txt");
            assertThat(retained.isTrashed()).isFalse();
            assertThat(retained.isLockedFile()).isFalse();
            verify(storage, never()).deleteObject(any());
            verify(storage, never()).generateDownloadUrl(any(), any(), any());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private Long owner() {
        var owner = new AppUser();
        owner.setLoginId("drive-" + UUID.randomUUID());
        owner.setDisplayName("드라이브 사용자");
        return users.saveAndFlush(owner).getId();
    }

    private Long sharedItemFor(Long recipientId) {
        return new TransactionTemplate(transactions).execute(status -> {
            AppUser source = new AppUser(); source.setLoginId("drive-source-" + UUID.randomUUID());
            source.setDisplayName("원본 소유자"); entities.persist(source);
            DriveItem item = new DriveItem(); item.setOwner(source); item.setOriginalName("photo.txt");
            item.setStoredName("photo.txt"); item.setStoragePath("test/" + UUID.randomUUID()); entities.persist(item);
            DriveShare share = new DriveShare(); share.setOwner(source); share.setItem(item);
            share.setRecipient(entities.getReference(AppUser.class, recipientId)); entities.persist(share);
            entities.flush();
            return item.getId();
        });
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("operation timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
