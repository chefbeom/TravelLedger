package com.playdata.calen.account.withdrawal;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable at-least-once task state. No external I/O is performed inside these transactions. */
@Service
@RequiredArgsConstructor
public class WithdrawalCleanupTaskStore {
    private static final Duration MAX_LEASE = Duration.ofMinutes(30);
    private static final Duration MAX_RETRY_DELAY = Duration.ofDays(1);
    private final WithdrawalCleanupTaskRepository tasks;
    private final WithdrawalPayloadCipher cipher;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;

    /** The caller must lock the parent request and commit this task with the source DB mutation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(UUID requestId, WithdrawalCleanupKind kind, String idempotencyKey, String payload) {
        if (requestId == null || kind == null) throw new IllegalArgumentException("Invalid withdrawal task.");
        String keyHash = cipher.fingerprint(requestId, "TASK_KEY_" + kind.name(), idempotencyKey);
        String payloadHash = cipher.fingerprint(requestId, "TASK_PAYLOAD_" + kind.name(), payload);
        Optional<WithdrawalCleanupTask> existing = tasks.findByRequestIdAndTaskKeyHash(requestId.toString(), keyHash);
        if (existing.isPresent()) {
            WithdrawalCleanupTask task = existing.get();
            if (task.getKind() != kind || !MessageDigest.isEqual(task.getPayloadHash().getBytes(StandardCharsets.US_ASCII),
                    payloadHash.getBytes(StandardCharsets.US_ASCII))) {
                throw new BadRequestException("같은 탈퇴 작업에 서로 다른 삭제 대상이 지정되었습니다.");
            }
            return UUID.fromString(task.getId());
        }
        UUID id = UUID.randomUUID();
        LocalDateTime now = databaseNow();
        WithdrawalCleanupTask task = new WithdrawalCleanupTask();
        task.setId(id.toString());
        task.setRequestId(requestId.toString());
        task.setKind(kind);
        task.setTaskKeyHash(keyHash);
        task.setPayloadHash(payloadHash);
        task.setEncryptedPayload(cipher.encrypt(requestId, id, kind.name(), payload));
        task.setState(WithdrawalCleanupTask.State.PENDING);
        task.setNextAttemptAt(now);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        tasks.saveAndFlush(task);
        return id;
    }

    /** Caller must hold the inactive user and parent request locks in that order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rearmKakaoAfterReconnect(UUID requestId, Long socialAccountId, String subject, boolean appVerified) {
        if (requestId == null || socialAccountId == null || socialAccountId <= 0
                || subject == null || !subject.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Invalid Kakao reconnection.");
        }
        String key = "social:" + socialAccountId;
        UUID id = enqueue(requestId, WithdrawalCleanupKind.KAKAO_UNLINK, key, subject);
        WithdrawalCleanupTask task = tasks.lockById(id.toString())
                .orElseThrow(() -> new ServiceUnavailableException("카카오 연결 정리 작업을 확인할 수 없습니다."));
        entities.refresh(task, LockModeType.PESSIMISTIC_WRITE);
        String expectedKey = cipher.fingerprint(requestId, "TASK_KEY_KAKAO_UNLINK", key);
        String expectedPayload = cipher.fingerprint(requestId, "TASK_PAYLOAD_KAKAO_UNLINK", subject);
        if (!requestId.toString().equals(task.getRequestId()) || task.getKind() != WithdrawalCleanupKind.KAKAO_UNLINK
                || task.getTaskKeyHash() == null || task.getPayloadHash() == null
                || !MessageDigest.isEqual(expectedKey.getBytes(StandardCharsets.US_ASCII), task.getTaskKeyHash().getBytes(StandardCharsets.US_ASCII))
                || !MessageDigest.isEqual(expectedPayload.getBytes(StandardCharsets.US_ASCII), task.getPayloadHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new ServiceUnavailableException("카카오 연결 정리 대상을 확인할 수 없습니다.");
        }
        LocalDateTime now = databaseNow();
        // A previous worker may still be finishing its external call. Invalidate its
        // claim immediately and postpone the next call until its lease has elapsed.
        LocalDateTime next = now;
        if (task.getState() == WithdrawalCleanupTask.State.PROCESSING
                && task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(now)) {
            next = task.getLeaseUntil().plusSeconds(1);
        } else if (("KAKAO_RECONNECTED".equals(task.getLastErrorCode())
                || "KAKAO_RECONNECT_UNVERIFIED".equals(task.getLastErrorCode()))
                && task.getNextAttemptAt() != null && task.getNextAttemptAt().isAfter(now)) {
            // Repeated callbacks, including an ambiguous one followed by a verified
            // one, must not erase the old in-flight worker's waiting period.
            next = task.getNextAttemptAt();
        }
        task.setEncryptedPayload(cipher.encrypt(requestId, id, task.getKind().name(), subject));
        // An unidentified app is not authority for another provider call. It still
        // invalidates an earlier success and needs manual reconciliation.
        task.setState(appVerified ? WithdrawalCleanupTask.State.PENDING : WithdrawalCleanupTask.State.FAILED);
        task.setAttempts(0);
        task.setNextAttemptAt(next);
        task.setClaimToken(null);
        task.setLeaseUntil(null);
        task.setLastErrorCode(appVerified ? "KAKAO_RECONNECTED" : "KAKAO_RECONNECT_UNVERIFIED");
        task.setUpdatedAt(now);
        tasks.saveAndFlush(task);
    }

    /** Expired claims may be reissued, so every eventual executor must be idempotent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claimNext(Set<WithdrawalCleanupKind> supportedKinds, Duration lease) {
        if (supportedKinds == null || supportedKinds.isEmpty() || supportedKinds.stream().anyMatch(Objects::isNull)) return Optional.empty();
        if (lease == null || lease.compareTo(Duration.ofSeconds(1)) < 0 || lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("Invalid withdrawal task lease.");
        }
        LocalDateTime queryTime = databaseNow();
        var candidates = tasks.findDueIds(queryTime, WithdrawalCleanupTask.State.PENDING,
                WithdrawalCleanupTask.State.PROCESSING, Set.copyOf(supportedKinds), PageRequest.of(0, 20));
        for (String id : candidates) {
            Optional<WithdrawalCleanupTask> locked = tasks.lockById(id);
            if (locked.isEmpty()) continue;
            WithdrawalCleanupTask task = locked.get();
            LocalDateTime now = databaseNow();
            if (!isDue(task, now) || !supportedKinds.contains(task.getKind())) continue;
            UUID requestId = UUID.fromString(task.getRequestId());
            UUID taskId = UUID.fromString(task.getId());
            String payload = cipher.decrypt(requestId, taskId, task.getKind().name(), task.getEncryptedPayload());
            UUID token = UUID.randomUUID();
            task.setState(WithdrawalCleanupTask.State.PROCESSING);
            task.setClaimToken(token.toString());
            task.setLeaseUntil(now.plus(lease));
            task.setAttempts(Math.addExact(task.getAttempts(), 1));
            task.setUpdatedAt(now);
            tasks.saveAndFlush(task);
            return Optional.of(new Claim(requestId, taskId, task.getKind(), token, task.getLeaseUntil(), task.getAttempts(), payload));
        }
        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean succeed(UUID taskId, UUID token) {
        if (taskId == null || token == null) return false;
        // Acquire the lock before the timestamped statement: on MariaDB CURRENT_TIMESTAMP
        // can be fixed at statement start, including any time spent waiting for a row lock.
        if (tasks.lockById(taskId.toString()).isEmpty()) return false;
        return jdbc.update("""
                update account_withdrawal_cleanup_tasks
                set state = 'SUCCEEDED', encrypted_payload = null, claim_token = null,
                    lease_until = null, last_error_code = null, updated_at = current_timestamp,
                    version = version + 1
                where id = ? and state = 'PROCESSING' and claim_token = ?
                  and lease_until > current_timestamp
                """, taskId.toString(), token.toString()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(UUID taskId, UUID token, boolean retryable, Duration retryDelay, String safeCode) {
        if (safeCode == null || !safeCode.matches("[A-Z0-9_]{1,50}") || retryDelay == null
                || retryDelay.isNegative() || retryDelay.compareTo(MAX_RETRY_DELAY) > 0) {
            throw new IllegalArgumentException("Invalid withdrawal task failure.");
        }
        if (taskId == null || token == null) return false;
        if (tasks.lockById(taskId.toString()).isEmpty()) return false;
        LocalDateTime now = databaseNow();
        return jdbc.update("""
                update account_withdrawal_cleanup_tasks
                set state = ?, next_attempt_at = ?, claim_token = null, lease_until = null,
                    last_error_code = ?, updated_at = current_timestamp, version = version + 1
                where id = ? and state = 'PROCESSING' and claim_token = ?
                  and lease_until > current_timestamp
                """, retryable ? "PENDING" : "FAILED", now.plus(retryDelay), safeCode,
                taskId.toString(), token.toString()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryFailed(UUID taskId) {
        if (taskId == null) return false;
        Optional<WithdrawalCleanupTask> locked = tasks.lockById(taskId.toString());
        if (locked.isEmpty() || locked.get().getState() != WithdrawalCleanupTask.State.FAILED
                || locked.get().getEncryptedPayload() == null
                || (locked.get().getKind() == WithdrawalCleanupKind.KAKAO_UNLINK
                    && "KAKAO_RECONNECT_UNVERIFIED".equals(locked.get().getLastErrorCode()))) return false;
        WithdrawalCleanupTask task = locked.get();
        LocalDateTime now = databaseNow();
        task.setState(WithdrawalCleanupTask.State.PENDING);
        task.setNextAttemptAt(now);
        task.setLastErrorCode(null);
        task.setUpdatedAt(now);
        return true;
    }

    private boolean isDue(WithdrawalCleanupTask task, LocalDateTime now) {
        return (task.getState() == WithdrawalCleanupTask.State.PENDING
                && task.getNextAttemptAt() != null && !task.getNextAttemptAt().isAfter(now))
                || (task.getState() == WithdrawalCleanupTask.State.PROCESSING
                && task.getLeaseUntil() != null && !task.getLeaseUntil().isAfter(now));
    }

    private LocalDateTime databaseNow() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp", (row, index) -> row.getObject(1, LocalDateTime.class));
        if (now == null) throw new ServiceUnavailableException("탈퇴 작업 저장소의 시간을 확인할 수 없습니다.");
        return now;
    }

    public static final class Claim {
        private final UUID requestId;
        private final UUID taskId;
        private final WithdrawalCleanupKind kind;
        private final UUID token;
        private final LocalDateTime leaseUntil;
        private final int attempt;
        private final String payload;

        Claim(UUID requestId, UUID taskId, WithdrawalCleanupKind kind, UUID token, LocalDateTime leaseUntil, int attempt, String payload) {
            this.requestId = requestId;
            this.taskId = taskId;
            this.kind = kind;
            this.token = token;
            this.leaseUntil = leaseUntil;
            this.attempt = attempt;
            this.payload = payload;
        }
        public UUID requestId() { return requestId; }
        public UUID taskId() { return taskId; }
        public WithdrawalCleanupKind kind() { return kind; }
        public UUID token() { return token; }
        public LocalDateTime leaseUntil() { return leaseUntil; }
        public int attempt() { return attempt; }
        public String payload() { return payload; }
        @Override public String toString() { return "WithdrawalCleanupClaim[redacted]"; }
    }
}
