package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.domain.AppUser;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A recovery proof, not a login credential. Only a hash of the email link is stored. */
@Entity
@Table(name = "withdrawal_email_challenges", indexes = {
        @Index(name = "idx_withdrawal_challenge_user", columnList = "user_id,created_at"),
        @Index(name = "idx_withdrawal_challenge_expires", columnList = "expires_at")
})
@Getter @Setter @NoArgsConstructor
public class WithdrawalEmailChallenge {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private AppUser user;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(name = "email_hash", nullable = false, length = 64) private String emailHash;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    @Column(name = "used_at") private LocalDateTime usedAt;
    @Column(name = "pin_failures", nullable = false) private int pinFailures;
    @Column(name = "pin_window_started_at") private LocalDateTime pinWindowStartedAt;
    @Column(name = "pin_blocked_until") private LocalDateTime pinBlockedUntil;
}
