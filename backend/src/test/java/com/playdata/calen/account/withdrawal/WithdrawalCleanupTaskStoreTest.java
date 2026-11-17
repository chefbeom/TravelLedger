package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.social.KakaoOAuthClient;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.SocialAccount;
import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:withdrawal-outbox-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "app.security.withdrawal-task-seal-key=test-withdrawal-secret-at-least-32-characters",
        "app.auth.kakao.expected-app-id=1234", "app.auth.kakao.admin-app-id=1234"
})
class WithdrawalCleanupTaskStoreTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final WithdrawalCleanupKind KIND = WithdrawalCleanupKind.FAMILY_MEDIA;
    @Autowired WithdrawalCleanupTaskStore store;
    @Autowired WithdrawalCleanupTaskRepository tasks;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired KakaoWithdrawalCleanupExecutor kakaoExecutor;
    @Autowired AppUserRepository users;
    @Autowired SocialAccountRepository socialAccounts;
    @Autowired AccountWithdrawalRequestRepository requests;
    @MockitoBean KakaoOAuthClient kakao;

    @BeforeEach void clearTestDatabase() { tasks.deleteAll(); socialAccounts.deleteAll(); requests.deleteAll(); }

    @Test void enqueueIsTransactionalEncryptedAndIdempotent() {
        UUID request = UUID.randomUUID();
        String target = "private-family-photo.jpg";
        assertThatThrownBy(() -> store.enqueue(request, KIND, "photo:1", target))
                .isInstanceOf(IllegalTransactionStateException.class);
        UUID id = enqueue(request, KIND, "photo:1", target);
        assertThat(enqueue(request, KIND, "photo:1", target)).isEqualTo(id);
        assertThat(tasks.count()).isEqualTo(1);
        WithdrawalCleanupTask task = tasks.findById(id.toString()).orElseThrow();
        assertThat(task.getEncryptedPayload()).doesNotContain(target);
        assertThat(task.getTaskKeyHash()).hasSize(43).doesNotContain("photo");
        assertThat(task.getPayloadHash()).hasSize(43).doesNotContain(target);
        assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
        assertThatThrownBy(() -> enqueue(request, KIND, "photo:1", "a-different-target"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test void rollingBackTheAccountTransactionAlsoRollsBackItsCleanupTask() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            store.enqueue(UUID.randomUUID(), KIND, "photo:1", "private-file");
            status.setRollbackOnly();
        });
        assertThat(tasks.count()).isZero();
    }

    @Test void claimIsKindScopedAndOnlyTheCurrentOwnerCanCompleteIt() {
        UUID request = UUID.randomUUID();
        UUID id = enqueue(request, KIND, "photo:1", "private-file");
        assertThat(store.claimNext(Set.of(WithdrawalCleanupKind.KAKAO_UNLINK), LEASE)).isEmpty();
        var claim = store.claimNext(Set.of(KIND), LEASE).orElseThrow();
        assertThat(claim.requestId()).isEqualTo(request);
        assertThat(claim.taskId()).isEqualTo(id);
        assertThat(claim.payload()).isEqualTo("private-file");
        assertThat(claim.toString()).doesNotContain("private-file").doesNotContain(claim.token().toString());
        assertThat(store.succeed(id, UUID.randomUUID())).isFalse();
        assertThat(store.succeed(id, claim.token())).isTrue();
        assertThat(store.succeed(id, claim.token())).isFalse();
        WithdrawalCleanupTask complete = tasks.findById(id.toString()).orElseThrow();
        assertThat(complete.getState()).isEqualTo(WithdrawalCleanupTask.State.SUCCEEDED);
        assertThat(complete.getEncryptedPayload()).isNull();
        assertThat(enqueue(request, KIND, "photo:1", "private-file")).isEqualTo(id);
    }

    @Test void retryableAndPermanentFailuresKeepTheirTargetForExplicitRetry() {
        UUID id = enqueue(UUID.randomUUID(), KIND, "photo:1", "private-file");
        var first = store.claimNext(Set.of(KIND), LEASE).orElseThrow();
        assertThat(store.fail(id, first.token(), true, Duration.ZERO, "STORE_UNAVAILABLE")).isTrue();
        var second = store.claimNext(Set.of(KIND), LEASE).orElseThrow();
        assertThat(tasks.findById(id.toString()).orElseThrow().getAttempts()).isEqualTo(2);
        assertThat(store.fail(id, second.token(), false, Duration.ZERO, "PERMISSION_DENIED")).isTrue();
        WithdrawalCleanupTask failed = tasks.findById(id.toString()).orElseThrow();
        assertThat(failed.getState()).isEqualTo(WithdrawalCleanupTask.State.FAILED);
        assertThat(failed.getEncryptedPayload()).doesNotContain("private-file");
        assertThat(failed.getLastErrorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(store.claimNext(Set.of(KIND), LEASE)).isEmpty();
        assertThat(store.retryFailed(id)).isTrue();
        assertThat(store.retryFailed(id)).isFalse();
        assertThat(store.claimNext(Set.of(KIND), LEASE).orElseThrow().payload()).isEqualTo("private-file");
        assertThatThrownBy(() -> store.fail(id, second.token(), false, Duration.ZERO, "private error details"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void executorCompletesARealEncryptedTaskOnlyAfterProviderConfirmation() {
        UUID id = enqueueVerifiedKakao("123456789");
        when(kakao.isUnlinkConfigured()).thenReturn(true);
        assertThat(kakaoExecutor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.SUCCEEDED);
        verify(kakao).unlinkAccount("123456789");
        WithdrawalCleanupTask task = tasks.findById(id.toString()).orElseThrow();
        assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.SUCCEEDED);
        assertThat(task.getEncryptedPayload()).isNull();
    }

    @Test void internalWorkerProcessesOnlyItsSupportedRealTasks() {
        UUID first = enqueueVerifiedKakao("123456789");
        UUID second = enqueueVerifiedKakao("223456789");
        UUID family = enqueue(UUID.randomUUID(), KIND, "family", "private-file");
        when(kakao.isUnlinkConfigured()).thenReturn(true);
        var worker = new WithdrawalCleanupWorker(kakaoExecutor);
        try {
            worker.runBatch();
        } finally {
            worker.stop();
        }
        verify(kakao).unlinkAccount("123456789");
        verify(kakao).unlinkAccount("223456789");
        assertThat(tasks.findById(first.toString()).orElseThrow().getState()).isEqualTo(WithdrawalCleanupTask.State.SUCCEEDED);
        assertThat(tasks.findById(second.toString()).orElseThrow().getState()).isEqualTo(WithdrawalCleanupTask.State.SUCCEEDED);
        assertThat(tasks.findById(family.toString()).orElseThrow().getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
    }

    @Test void executorKeepsARealEncryptedTaskPendingWhenProviderResultIsUnknown() {
        UUID id = enqueueVerifiedKakao("123456789");
        when(kakao.isUnlinkConfigured()).thenReturn(true);
        doThrow(new ServiceUnavailableException("provider result unknown")).when(kakao).unlinkAccount("123456789");
        assertThat(kakaoExecutor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.RETRY_PENDING);
        WithdrawalCleanupTask task = tasks.findById(id.toString()).orElseThrow();
        assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
        assertThat(task.getLastErrorCode()).isEqualTo("KAKAO_UNLINK_UNCONFIRMED");
        assertThat(task.getEncryptedPayload()).doesNotContain("123456789");
    }

    @Test void expiredOwnerCannotCompleteOrFailAfterAnotherWorkerClaimsTheTask() {
        UUID id = enqueue(UUID.randomUUID(), KIND, "photo:1", "private-file");
        var stale = store.claimNext(Set.of(KIND), LEASE).orElseThrow();
        LocalDateTime now = jdbc.queryForObject("select current_timestamp", (row, index) -> row.getObject(1, LocalDateTime.class));
        jdbc.update("update account_withdrawal_cleanup_tasks set lease_until = ? where id = ?",
                Timestamp.valueOf(now.minusMinutes(1)), id.toString());
        assertThat(store.succeed(id, stale.token())).isFalse();
        var current = store.claimNext(Set.of(KIND), LEASE).orElseThrow();
        assertThat(current.token()).isNotEqualTo(stale.token());
        assertThat(store.fail(id, stale.token(), false, Duration.ZERO, "STALE")).isFalse();
        assertThat(store.succeed(id, current.token())).isTrue();
    }

    @Test void simultaneousWorkersCannotBothClaimTheSameTask() throws Exception {
        UUID id = enqueue(UUID.randomUUID(), KIND, "photo:1", "private-file");
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { ready.countDown(); start.await(); return store.claimNext(Set.of(KIND), LEASE); });
            var second = pool.submit(() -> { ready.countDown(); start.await(); return store.claimNext(Set.of(KIND), LEASE); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Optional<WithdrawalCleanupTaskStore.Claim>> claims = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(claims.stream().filter(Optional::isPresent)).hasSize(1);
            assertThat(tasks.findById(id.toString()).orElseThrow().getAttempts()).isEqualTo(1);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test void workerWaitingOnALockCannotCompleteAfterItsLeaseExpires() throws Exception {
        UUID id = enqueue(UUID.randomUUID(), KIND, "photo:1", "private-file");
        var claim = store.claimNext(Set.of(KIND), Duration.ofSeconds(2)).orElseThrow();
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                tasks.lockById(id.toString()).orElseThrow();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("lock release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var completion = pool.submit(() -> store.succeed(id, claim.token()));
            LocalDateTime dbNow;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            do {
                Thread.sleep(50);
                dbNow = jdbc.queryForObject("select current_timestamp", (row, index) -> row.getObject(1, LocalDateTime.class));
            } while (dbNow.isBefore(claim.leaseUntil()) && System.nanoTime() < deadline);
            assertThat(dbNow).isAfterOrEqualTo(claim.leaseUntil());
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(completion.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(tasks.findById(id.toString()).orElseThrow().getState())
                    .isEqualTo(WithdrawalCleanupTask.State.PROCESSING);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private UUID enqueueVerifiedKakao(String subject) {
        return new TransactionTemplate(transactions).execute(status -> {
            AppUser user = new AppUser();
            user.setLoginId("outbox-" + UUID.randomUUID());
            user.setDisplayName("테스트");
            user.setActive(false);
            user = users.saveAndFlush(user);
            SocialAccount account = new SocialAccount();
            account.setUser(user);
            account.setProvider(SocialLoginProvider.KAKAO);
            account.setProviderUserId(subject);
            account.setProviderAppId(1234L);
            socialAccounts.saveAndFlush(account);
            UUID requestId = UUID.randomUUID();
            LocalDateTime now = LocalDateTime.now();
            AccountWithdrawalRequest request = new AccountWithdrawalRequest();
            request.setId(requestId.toString());
            request.setUserId(user.getId());
            request.setState(AccountWithdrawalRequest.State.IN_PROGRESS);
            request.setRequestedAt(now);
            request.setUpdatedAt(now);
            requests.saveAndFlush(request);
            return store.enqueue(requestId, WithdrawalCleanupKind.KAKAO_UNLINK, "social:" + account.getId(), subject);
        });
    }

    private UUID enqueue(UUID request, WithdrawalCleanupKind kind, String key, String payload) {
        return new TransactionTemplate(transactions).execute(status -> store.enqueue(request, kind, key, payload));
    }
}
