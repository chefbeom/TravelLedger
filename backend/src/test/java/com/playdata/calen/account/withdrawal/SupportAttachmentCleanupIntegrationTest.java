package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.SupportInquiry;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.SupportInquiryRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
        "spring.datasource.url=jdbc:h2:mem:support-cleanup-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class SupportAttachmentCleanupIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired SupportInquiryRepository inquiries;
    @Autowired AccountWithdrawalRequestRepository requests;
    @Autowired WithdrawalCleanupTaskRepository tasks;
    @Autowired WithdrawalCleanupTaskStore store;
    @Autowired SupportAttachmentCleanupHandler handler;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @MockitoBean SecureSupportAttachmentFiles files;
    private record Fixture(long owner, UUID request, UUID task, SupportAttachmentTarget target) {}

    @BeforeEach void clearIsolatedTasks() {
        tasks.deleteAllInBatch(); requests.deleteAllInBatch(); inquiries.deleteAllInBatch();
    }

    @Test void confirmedDeletionCompletesOnlyThatTaskAndErasesItsPayload() throws Exception {
        var f = fixture(true, false);
        when(files.deleteExact(f.target())).thenReturn(SecureSupportAttachmentFiles.Outcome.DELETED);
        var claim = claim();
        assertThat(handler.handle(claim)).isEqualTo(SupportAttachmentCleanupHandler.Result.SUCCEEDED);
        var task = tasks.findById(f.task().toString()).orElseThrow();
        assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.SUCCEEDED);
        assertThat(task.getEncryptedPayload()).isNull();
        verify(files).deleteExact(f.target());
    }

    @Test void anotherInquiryReferencingThePathPreventsFileDeletion() throws Exception {
        var f = fixture(true, false);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var other = user(true); users.saveAndFlush(other);
            var inquiry = new SupportInquiry(); inquiry.setSender(other); inquiry.setTitle("other"); inquiry.setContent("keep");
            inquiry.setAttachmentStoragePath(f.target().path()); inquiries.saveAndFlush(inquiry);
        });
        assertThat(handler.handle(claim())).isEqualTo(SupportAttachmentCleanupHandler.Result.MANUAL_REVIEW);
        var task = tasks.findById(f.task().toString()).orElseThrow();
        assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.FAILED);
        assertThat(task.getLastErrorCode()).isEqualTo("SUPPORT_STILL_REFERENCED");
        assertThat(task.getEncryptedPayload()).isNotNull();
        verifyNoInteractions(files);
    }

    @Test void missingFileOrLegacyPayloadDoesNotCountAsCompleted() throws Exception {
        var missing = fixture(true, false);
        when(files.deleteExact(missing.target())).thenReturn(SecureSupportAttachmentFiles.Outcome.MISSING);
        assertThat(handler.handle(claim())).isEqualTo(SupportAttachmentCleanupHandler.Result.MANUAL_REVIEW);
        assertThat(tasks.findById(missing.task().toString()).orElseThrow().getLastErrorCode()).isEqualTo("SUPPORT_FILE_MISSING");
        var legacy = fixture(true, true);
        assertThat(handler.handle(claim())).isEqualTo(SupportAttachmentCleanupHandler.Result.MANUAL_REVIEW);
        assertThat(tasks.findById(legacy.task().toString()).orElseThrow().getLastErrorCode()).isEqualTo("SUPPORT_PROVENANCE_MISSING");
        verify(files, times(1)).deleteExact(any());
    }

    @Test void wrongStorageIsRetriedButActiveUserAndExpiredLeaseNeverDelete() throws Exception {
        var different = fixture(true, false);
        when(files.deleteExact(different.target())).thenReturn(SecureSupportAttachmentFiles.Outcome.WRONG_STORAGE);
        assertThat(handler.handle(claim())).isEqualTo(SupportAttachmentCleanupHandler.Result.RETRY_PENDING);
        assertThat(tasks.findById(different.task().toString()).orElseThrow().getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
        var active = fixture(false, false);
        assertThat(handler.handle(claim())).isEqualTo(SupportAttachmentCleanupHandler.Result.MANUAL_REVIEW);
        assertThat(tasks.findById(active.task().toString()).orElseThrow().getLastErrorCode()).isEqualTo("SUPPORT_ACCOUNT_STATE_INVALID");
        var stale = fixture(true, false); var oldClaim = claim();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var task = tasks.findById(stale.task().toString()).orElseThrow();
            task.setLeaseUntil(LocalDateTime.now().minusMinutes(1));
        });
        assertThat(handler.handle(oldClaim)).isEqualTo(SupportAttachmentCleanupHandler.Result.LOST_LEASE);
        verify(files, times(1)).deleteExact(any());
    }

    @Test void duplicateWorkersCannotDeleteTheSameClaimTwice() throws Exception {
        var f = fixture(true, false); var claim = claim();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            when(files.deleteExact(f.target())).thenAnswer(call -> {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                return SecureSupportAttachmentFiles.Outcome.DELETED;
            });
            var first = pool.submit(() -> handler.handle(claim));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> handler.handle(claim));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(SupportAttachmentCleanupHandler.Result.SUCCEEDED);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(SupportAttachmentCleanupHandler.Result.LOST_LEASE);
            verify(files, times(1)).deleteExact(f.target());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private Fixture fixture(boolean inactive, boolean legacy) throws Exception {
        var owner = new TransactionTemplate(transactions).execute(status -> users.saveAndFlush(user(!inactive)));
        UUID requestId = UUID.randomUUID();
        var target = new SupportAttachmentTarget(owner.getId(), "user-" + owner.getId() + "/" + UUID.randomUUID() + ".png",
                "test_storage_identifier_001", "a".repeat(64));
        String payload = legacy ? target.path() : json.writeValueAsString(target);
        UUID taskId = new TransactionTemplate(transactions).execute(status -> {
            var request = new AccountWithdrawalRequest(); request.setId(requestId.toString()); request.setUserId(owner.getId());
            request.setState(AccountWithdrawalRequest.State.IN_PROGRESS); request.setRequestedAt(LocalDateTime.now());
            request.setUpdatedAt(LocalDateTime.now()); requests.saveAndFlush(request);
            return store.enqueue(requestId, WithdrawalCleanupKind.SUPPORT_ATTACHMENT, "support:" + target.path(), payload);
        });
        return new Fixture(owner.getId(), requestId, taskId, target);
    }

    private WithdrawalCleanupTaskStore.Claim claim() {
        return store.claimNext(Set.of(WithdrawalCleanupKind.SUPPORT_ATTACHMENT), Duration.ofMinutes(5)).orElseThrow();
    }

    private AppUser user(boolean active) {
        var user = new AppUser(); user.setLoginId("support-cleanup-" + UUID.randomUUID()); user.setDisplayName("Test");
        user.setActive(active); return user;
    }
}
