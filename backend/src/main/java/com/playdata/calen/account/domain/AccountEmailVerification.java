package com.playdata.calen.account.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "account_email_verifications", indexes = {
        @Index(name = "idx_account_email_verification_user", columnList = "user_id,created_at"),
        @Index(name = "idx_account_email_verification_expires", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AccountEmailVerification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;
    @Column(name = "target_email", nullable = false, length = 254)
    private String targetEmail;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
    @Column(name = "used_at")
    private LocalDateTime usedAt;
}
