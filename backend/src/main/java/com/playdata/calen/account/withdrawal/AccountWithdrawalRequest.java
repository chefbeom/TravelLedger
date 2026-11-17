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

/** No user FK: the cleanup request must survive removal of the source account. */
@Entity
@Table(name = "account_withdrawal_requests",
        uniqueConstraints = @UniqueConstraint(name = "uk_account_withdrawal_user", columnNames = "user_id"),
        indexes = @Index(name = "idx_account_withdrawal_state", columnList = "state,updated_at"))
@Getter @Setter @NoArgsConstructor
public class AccountWithdrawalRequest {
    @Id @Column(length = 36, nullable = false) private String id;
    @Column(name = "user_id") private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private State state;
    @Column(name = "requested_at", nullable = false) private LocalDateTime requestedAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Version @Column(nullable = false) private long version;

    /** A final state will only be added when all domains and backup handling are implemented. */
    public enum State { IN_PROGRESS }
}
