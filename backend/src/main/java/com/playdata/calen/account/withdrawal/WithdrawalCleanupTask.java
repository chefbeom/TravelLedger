package com.playdata.calen.account.withdrawal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** No user FK: pending cleanup must survive deletion of the source account. */
@Entity
@Table(name = "account_withdrawal_cleanup_tasks",
        uniqueConstraints = @UniqueConstraint(name = "uk_withdrawal_cleanup_key", columnNames = {"request_id", "task_key_hash"}),
        indexes = {
                @Index(name = "idx_withdrawal_cleanup_due", columnList = "state,next_attempt_at"),
                @Index(name = "idx_withdrawal_cleanup_lease", columnList = "state,lease_until")
        })
@Getter @Setter @NoArgsConstructor
public class WithdrawalCleanupTask {
    @Id @Column(length = 36, nullable = false) private String id;
    @Column(name = "request_id", length = 36, nullable = false) private String requestId;
    @Enumerated(EnumType.STRING) @Column(length = 40, nullable = false) private WithdrawalCleanupKind kind;
    @Column(name = "task_key_hash", length = 43, nullable = false) private String taskKeyHash;
    @Column(name = "payload_hash", length = 43, nullable = false) private String payloadHash;
    @Column(name = "encrypted_payload", length = 4096) private String encryptedPayload;
    @Enumerated(EnumType.STRING) @Column(length = 20, nullable = false) private State state;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private LocalDateTime nextAttemptAt;
    @Column(name = "claim_token", length = 36) private String claimToken;
    @Column(name = "lease_until") private LocalDateTime leaseUntil;
    @Column(name = "last_error_code", length = 50) private String lastErrorCode;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Version @Column(nullable = false) private long version;

    public enum State { PENDING, PROCESSING, SUCCEEDED, FAILED }
}
