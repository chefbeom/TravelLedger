package com.playdata.calen.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_users")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String loginId;

    @Column(nullable = false, length = 80)
    private String displayName;

    @Column(length = 254, unique = true)
    private String email;

    // Social-only accounts do not have a local password.
    @Column(length = 100)
    private String passwordHash;

    @Column(length = 100)
    private String secondaryPinHash;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AppUserRole role = AppUserRole.USER;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String householdAggregateSettingsJson;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private boolean emailVerified = true;

    /** An anonymized abandoned signup is never a reusable or reactivatable account. */
    @Column(name = "expired_email_signup", nullable = false)
    private boolean expiredEmailSignup = false;

    // Shared across app nodes and token reissues while an email signup is pending.
    @Column(name = "signup_pin_failures", nullable = false)
    private int signupPinFailures;

    @Column(name = "signup_pin_window_started_at")
    private java.time.LocalDateTime signupPinWindowStartedAt;

    @Column(name = "signup_pin_blocked_until")
    private java.time.LocalDateTime signupPinBlockedUntil;
}
