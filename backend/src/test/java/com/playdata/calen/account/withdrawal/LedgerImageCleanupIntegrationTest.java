package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.LedgerImageWithdrawalCleanup.ImageTask;
import com.playdata.calen.common.storage.LedgerImageStorageMarker;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.ledger.domain.LedgerImageAnalysisRequest;
import com.playdata.calen.ledger.repository.LedgerImageAnalysisRequestRepository;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.account-withdrawal.internal-workflow-enabled=true",
        "app.security.withdrawal-task-seal-key=test-withdrawal-secret-at-least-32-characters",
        "app.ledger.ocr.image-storage-id=fixture_storage_id_1234",
        "spring.datasource.url=jdbc:h2:mem:ledger-image-cleanup-${random.uuid};MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class LedgerImageCleanupIntegrationTest {
    private static final String ID = "fixture_storage_id_1234";
    @Autowired AppUserRepository users;
    @Autowired AccountWithdrawalRequestRepository requests;
    @Autowired WithdrawalCleanupTaskRepository tasks;
    @Autowired WithdrawalCleanupTaskStore store;
    @Autowired LedgerImageCleanupExecutor executor;
    @Autowired LedgerImageAnalysisRequestRepository images;
    @Autowired DriveItemRepository driveItems;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @MockitoBean MinioClient minio;

    @BeforeEach void isolateTasks() { tasks.deleteAll(); }

    @Test void validPrivateImageRemovesOnlyItsRecordedVersionBeforeTaskSuccess() throws Exception {
        Fixture fixture = fixture(false, true);
        marker(ID);
        var original = item(fixture.key(), "version-1");
        when(minio.listObjects(any(ListObjectsArgs.class))).thenReturn(
                List.of(new Result<>(original)), List.of());
        assertThat(executor.processNext()).isEqualTo(LedgerImageCleanupExecutor.Result.SUCCEEDED);
        verify(minio).removeObject(org.mockito.ArgumentMatchers.argThat(args -> args.bucket().equals("cloud-bucket")
                && args.object().equals(fixture.key()) && args.versionId().equals("version-1")));
        var task = tasks.findById(fixture.taskId().toString()).orElseThrow();
        assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.SUCCEEDED);
        assertThat(task.getEncryptedPayload()).isNull();
    }

    @Test void activeAccountOrLegacyMissingProvenanceNeverReachesStorage() throws Exception {
        Fixture active = fixture(true, true);
        assertThat(executor.processNext()).isEqualTo(LedgerImageCleanupExecutor.Result.MANUAL_REVIEW);
        assertThat(tasks.findById(active.taskId().toString()).orElseThrow().getEncryptedPayload()).isNotNull();
        Fixture legacy = fixture(false, false);
        assertThat(executor.processNext()).isEqualTo(LedgerImageCleanupExecutor.Result.MANUAL_REVIEW);
        assertThat(tasks.findById(legacy.taskId().toString()).orElseThrow().getEncryptedPayload()).isNotNull();
        verify(minio, never()).getObject(any(GetObjectArgs.class));
        verify(minio, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test void anotherUsersReferencePreventsDeletion() throws Exception {
        Fixture fixture = fixture(false, true);
        reference(fixture.key());
        assertThat(executor.processNext()).isEqualTo(LedgerImageCleanupExecutor.Result.MANUAL_REVIEW);
        verify(minio, never()).getObject(any(GetObjectArgs.class));
        verify(minio, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test void anotherUsersDriveFileReferencePreventsDeletion() throws Exception {
        Fixture fixture = fixture(false, true);
        AppUser other = new AppUser(); other.setLoginId("drive-other-" + UUID.randomUUID()); other.setDisplayName("다른 사용자");
        other = users.saveAndFlush(other);
        DriveItem item = new DriveItem(); item.setOwner(other); item.setOriginalName("보존할 파일");
        item.setStoredName("stored.jpg"); item.setStoragePath(fixture.key()); driveItems.saveAndFlush(item);
        assertThat(executor.processNext()).isEqualTo(LedgerImageCleanupExecutor.Result.MANUAL_REVIEW);
        verify(minio, never()).getObject(any(GetObjectArgs.class));
        verify(minio, never()).removeObject(any(RemoveObjectArgs.class));
        assertThat(driveItems.findById(item.getId())).isPresent();
    }

    @Test void referenceAddedDuringStorageListingIsRecheckedImmediatelyBeforeDelete() throws Exception {
        Fixture fixture = fixture(false, true);
        marker(ID);
        when(minio.listObjects(any(ListObjectsArgs.class))).thenAnswer(invocation -> {
            reference(fixture.key());
            return List.of(new Result<>(item(fixture.key(), "version-1")));
        });
        assertThat(executor.processNext()).isEqualTo(LedgerImageCleanupExecutor.Result.MANUAL_REVIEW);
        verify(minio, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test void markerFailureLeavesTheEncryptedTaskForRetry() throws Exception {
        Fixture fixture = fixture(false, true);
        marker("different_storage_id_1234");
        assertThat(executor.processNext()).isEqualTo(LedgerImageCleanupExecutor.Result.RETRY_PENDING);
        var task = tasks.findById(fixture.taskId().toString()).orElseThrow();
        assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
        assertThat(task.getEncryptedPayload()).isNotNull();
        verify(minio, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test void taskForAnotherStorageIdentityDoesNotContactThisEndpoint() throws Exception {
        Fixture fixture = fixture(false, true, "different_storage_id_1234");
        assertThat(executor.processNext()).isEqualTo(LedgerImageCleanupExecutor.Result.RETRY_PENDING);
        var pending = tasks.findById(fixture.taskId().toString()).orElseThrow();
        assertThat(pending.getLastErrorCode()).isEqualTo("IMAGE_STORAGE_ID_MISMATCH");
        assertThat(pending.getEncryptedPayload()).isNotNull();
        verify(minio, never()).getObject(any(GetObjectArgs.class));
        verify(minio, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test void leaseExpiringDuringStorageListingStopsBeforeDelete() throws Exception {
        Fixture fixture = fixture(false, true);
        marker(ID);
        when(minio.listObjects(any(ListObjectsArgs.class))).thenAnswer(invocation -> {
            var task = tasks.findById(fixture.taskId().toString()).orElseThrow();
            task.setLeaseUntil(LocalDateTime.now().minusMinutes(1)); tasks.saveAndFlush(task);
            return List.of(new Result<>(item(fixture.key(), "version-1")));
        });
        assertThat(executor.processNext()).isEqualTo(LedgerImageCleanupExecutor.Result.LOST_LEASE);
        verify(minio, never()).removeObject(any(RemoveObjectArgs.class));
        assertThat(tasks.findById(fixture.taskId().toString()).orElseThrow().getEncryptedPayload()).isNotNull();
    }

    @Test void accountReactivatedDuringStorageListingStopsBeforeDelete() throws Exception {
        Fixture fixture = fixture(false, true);
        marker(ID);
        when(minio.listObjects(any(ListObjectsArgs.class))).thenAnswer(invocation -> {
            var user = users.findById(fixture.userId()).orElseThrow();
            user.setActive(true); users.saveAndFlush(user);
            return List.of(new Result<>(item(fixture.key(), "version-1")));
        });
        assertThat(executor.processNext()).isEqualTo(LedgerImageCleanupExecutor.Result.MANUAL_REVIEW);
        verify(minio, never()).removeObject(any(RemoveObjectArgs.class));
        assertThat(users.findById(fixture.userId()).orElseThrow().isActive()).isTrue();
    }

    @Test void lostDeleteResponseAndThenMissingVersionRequiresManualReconciliation() throws Exception {
        Fixture fixture = fixture(false, true);
        marker(ID);
        var original = item(fixture.key(), "version-1");
        when(minio.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(new Result<>(original)), List.of());
        org.mockito.Mockito.doThrow(new java.io.IOException("unconfirmed provider response"))
                .when(minio).removeObject(any(RemoveObjectArgs.class));
        assertThat(executor.processNext()).isEqualTo(LedgerImageCleanupExecutor.Result.RETRY_PENDING);
        var pending = tasks.findById(fixture.taskId().toString()).orElseThrow();
        pending.setNextAttemptAt(LocalDateTime.now().minusMinutes(1)); tasks.saveAndFlush(pending);
        assertThat(executor.processNext()).isEqualTo(LedgerImageCleanupExecutor.Result.MANUAL_REVIEW);
        var failed = tasks.findById(fixture.taskId().toString()).orElseThrow();
        assertThat(failed.getState()).isEqualTo(WithdrawalCleanupTask.State.FAILED);
        assertThat(failed.getEncryptedPayload()).isNotNull();
        assertThat(failed.getLastErrorCode()).isEqualTo("IMAGE_VERSION_UNCONFIRMED");
    }

    private Fixture fixture(boolean active, boolean verified) {
        return fixture(active, verified, ID);
    }

    private Fixture fixture(boolean active, boolean verified, String storageId) {
        return new TransactionTemplate(transactions).execute(status -> {
            try {
                AppUser user = new AppUser(); user.setLoginId("image-cleanup-" + UUID.randomUUID());
                user.setDisplayName("테스트 사용자"); user.setActive(active); user = users.saveAndFlush(user);
                UUID requestId = UUID.randomUUID(); LocalDateTime now = LocalDateTime.now();
                AccountWithdrawalRequest request = new AccountWithdrawalRequest(); request.setId(requestId.toString());
                request.setUserId(user.getId()); request.setState(AccountWithdrawalRequest.State.IN_PROGRESS);
                request.setRequestedAt(now); request.setUpdatedAt(now); requests.saveAndFlush(request);
                String key = "ledger-image-analysis/" + user.getId() + "/11/" + UUID.randomUUID() + ".jpg";
                ImageTask target = new ImageTask(user.getId(), 11L, key, "cloud-bucket", verified ? storageId : null, verified ? "version-1" : null);
                UUID taskId = store.enqueue(requestId, WithdrawalCleanupKind.LEDGER_IMAGE, "image:" + key, json.writeValueAsString(target));
                return new Fixture(user.getId(), taskId, key);
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        });
    }

    private void reference(String key) {
        AppUser other = new AppUser(); other.setLoginId("other-" + UUID.randomUUID()); other.setDisplayName("다른 사용자");
        other = users.saveAndFlush(other);
        var image = new LedgerImageAnalysisRequest(); image.setOwner(other); image.setImageObjectKey(key);
        images.saveAndFlush(image);
    }

    private void marker(String contents) throws Exception {
        when(minio.getObject(any(GetObjectArgs.class))).thenAnswer(invocation -> new GetObjectResponse(
                new okhttp3.Headers.Builder().build(), "cloud-bucket", "fixture", LedgerImageStorageMarker.OBJECT_KEY,
                new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8))));
    }

    private Item item(String key, String version) {
        Item item = org.mockito.Mockito.mock(Item.class);
        when(item.objectName()).thenReturn(key); when(item.versionId()).thenReturn(version); when(item.isDeleteMarker()).thenReturn(false);
        return item;
    }

    private record Fixture(Long userId, UUID taskId, String key) {}
}
