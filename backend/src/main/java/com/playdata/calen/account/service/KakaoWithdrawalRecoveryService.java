package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.dto.AccountWithdrawalPreviewResponse;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.account.social.KakaoOAuthClient;
import com.playdata.calen.account.withdrawal.AccountWithdrawalStartService;
import com.playdata.calen.account.withdrawal.AdministratorWithdrawalGuard;
import com.playdata.calen.account.withdrawal.WithdrawalEmailChallenge;
import com.playdata.calen.account.withdrawal.WithdrawalEmailChallengeRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.exception.TooManyRequestsException;
import com.playdata.calen.familyalbum.service.FamilyAlbumWithdrawalService;
import com.playdata.calen.travel.service.TravelShareGroupWithdrawalService;
import com.playdata.calen.travel.service.TravelPlanWithdrawalService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Internal recovery-only authorization. It must not issue a login session or be exposed yet. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class KakaoWithdrawalRecoveryService {
    private static final Duration COOLDOWN = Duration.ofMinutes(1);
    private static final Duration MAX_VALIDITY = Duration.ofMinutes(30);
    private static final Duration PIN_WINDOW = Duration.ofMinutes(15);
    private static final int MAX_PIN_FAILURES = 5;
    private final AppUserRepository users;
    private final AppUserService appUsers;
    private final SocialAccountRepository social;
    private final WithdrawalEmailChallengeRepository challenges;
    private final EmailVerificationService verification;
    private final WithdrawalRecoveryMailSender mail;
    private final FamilyAlbumWithdrawalService albums;
    private final TravelShareGroupWithdrawalService groups;
    private final TravelPlanWithdrawalService travelPlans;
    private final KakaoOAuthClient kakao;
    private final AccountWithdrawalStartService withdrawal;
    private final AdministratorWithdrawalGuard administrators;
    private final EntityManager entities;
    private final JdbcTemplate jdbc;

    /** The caller must apply IP/abuse limits and return the same response for every email. */
    @Transactional
    public void requestEmailProof(String rawEmail) {
        verification.ensureConfigured();
        String email = appUsers.normalizeEmailFormat(rawEmail);
        Long userId = users.findIdByEmailIgnoreCase(email).orElse(null);
        if (userId == null) return;
        AppUser user = users.lockForAccountSecurityUpdate(userId).orElse(null);
        if (user != null) entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (!eligible(user) || !email.equalsIgnoreCase(user.getEmail())) return;
        LocalDateTime now = databaseNow();
        var latest = challenges.findTopByUserIdOrderByCreatedAtDesc(userId);
        latest.ifPresent(challenge -> entities.refresh(challenge, LockModeType.PESSIMISTIC_WRITE));
        if (latest.isPresent() && latest.get().getCreatedAt().plus(COOLDOWN).isAfter(now)) return;
        String rawToken = verification.createRawToken();
        Duration validity = verification.validity().compareTo(MAX_VALIDITY) < 0 ? verification.validity() : MAX_VALIDITY;
        var challenge = new WithdrawalEmailChallenge();
        challenge.setUser(user);
        challenge.setTokenHash(verification.hashToken(rawToken));
        challenge.setEmailHash(emailHash(user.getEmail()));
        challenge.setCreatedAt(now);
        challenge.setExpiresAt(now.plus(validity));
        latest.ifPresent(previous -> {
            challenge.setPinFailures(previous.getPinFailures());
            challenge.setPinWindowStartedAt(previous.getPinWindowStartedAt());
            challenge.setPinBlockedUntil(previous.getPinBlockedUntil());
        });
        try {
            mail.send(user.getEmail(), verification.buildWithdrawalRecoveryUrl(rawToken), validity);
        } catch (RuntimeException failure) {
            // Do not invalidate the existing proof if delivery fails, or disclose the address.
            log.warn("Withdrawal recovery email delivery failed");
            return;
        }
        challenges.invalidateOpenTokens(userId, now);
        challenges.saveAndFlush(challenge);
    }

    @Transactional(noRollbackFor = {RecoveryPinMismatch.class, RecoveryPinLimited.class})
    public AccountWithdrawalPreviewResponse preview(String rawToken, String pin) {
        var proof = lockProof(rawToken, false);
        verifyPin(proof, pin);
        return new AccountWithdrawalPreviewResponse(false, true, kakao.isUnlinkConfigured(),
                albums.requiredTransfers(proof.user().getId()), groups.requiredTransfers(proof.user().getId()),
                travelPlans.requiredTransfers(proof.user().getId()), travelPlans.foreignMediaSummary(proof.user().getId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = {RecoveryPinMismatch.class, RecoveryPinLimited.class})
    public UUID start(String rawToken, String pin, Map<Long, Long> familyOwners, Map<Long, Long> groupOwners) {
        return start(rawToken, pin, familyOwners, groupOwners, Map.of());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = {RecoveryPinMismatch.class, RecoveryPinLimited.class})
    public UUID start(String rawToken, String pin, Map<Long, Long> familyOwners, Map<Long, Long> groupOwners,
                      Map<Long, Long> travelPlanOwners) {
        var proof = lockProof(rawToken, true);
        verifyPin(proof, pin);
        // The start step rechecks the PIN and revokes every open recovery proof atomically.
        return withdrawal.start(proof.user().getId(), pin, familyOwners, groupOwners, travelPlanOwners);
    }

    private Proof lockProof(String rawToken, boolean startingWithdrawal) {
        String hash = verification.hashToken(rawToken);
        Long userId = challenges.findUserIdByTokenHash(hash).orElseThrow(this::invalidProof);
        if (startingWithdrawal) administrators.lockAndCheckIfAdministrator(userId);
        AppUser user = users.lockForAccountSecurityUpdate(userId).orElseThrow(this::invalidProof);
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var challenge = challenges.findForUpdateByTokenHash(hash).orElseThrow(this::invalidProof);
        entities.refresh(challenge, LockModeType.PESSIMISTIC_WRITE);
        if (!eligible(user) || challenge.getUsedAt() != null || !databaseNow().isBefore(challenge.getExpiresAt())
                || !MessageDigest.isEqual(emailHash(user.getEmail()).getBytes(StandardCharsets.US_ASCII),
                        challenge.getEmailHash().getBytes(StandardCharsets.US_ASCII))) throw invalidProof();
        return new Proof(user, challenge);
    }

    /** The user and latest proof are locked by lockProof; failed attempts must commit on mismatch. */
    private void verifyPin(Proof proof, String pin) {
        LocalDateTime now = databaseNow();
        var challenge = proof.challenge();
        if (challenge.getPinFailures() < 0 || challenge.getPinFailures() > MAX_PIN_FAILURES
                || (challenge.getPinFailures() > 0 && challenge.getPinWindowStartedAt() == null)) {
            throw new ServiceUnavailableException("인증 시도 상태를 확인할 수 없습니다.");
        }
        if (challenge.getPinBlockedUntil() != null && now.isBefore(challenge.getPinBlockedUntil())) {
            throw new RecoveryPinLimited();
        }
        if (challenge.getPinFailures() == MAX_PIN_FAILURES
                && now.isBefore(challenge.getPinWindowStartedAt().plus(PIN_WINDOW))) {
            // Do not let an inconsistent or prematurely cleared timestamp reset the limit.
            challenge.setPinBlockedUntil(challenge.getPinWindowStartedAt().plus(PIN_WINDOW));
            throw new RecoveryPinLimited();
        }
        switch (appUsers.checkSignupPin(proof.user(), pin)) {
            case MISMATCH -> {
                LocalDateTime began = challenge.getPinWindowStartedAt();
                int count = challenge.getPinFailures();
                if (began == null || !now.isBefore(began.plus(PIN_WINDOW))) {
                    began = now;
                    count = 0;
                }
                int next = count >= MAX_PIN_FAILURES ? MAX_PIN_FAILURES : count + 1;
                challenge.setPinWindowStartedAt(began);
                challenge.setPinFailures(next);
                if (next >= MAX_PIN_FAILURES) {
                    challenge.setPinBlockedUntil(now.plus(PIN_WINDOW));
                    throw new RecoveryPinLimited();
                }
                challenge.setPinBlockedUntil(null);
                throw new RecoveryPinMismatch();
            }
            case LIMITED -> {
                challenge.setPinFailures(MAX_PIN_FAILURES);
                challenge.setPinWindowStartedAt(now);
                challenge.setPinBlockedUntil(now.plus(PIN_WINDOW));
                throw new RecoveryPinLimited();
            }
            case MATCH -> {
                challenge.setPinFailures(0);
                challenge.setPinWindowStartedAt(null);
                challenge.setPinBlockedUntil(null);
            }
        }
    }

    private boolean eligible(AppUser user) {
        return user != null && user.isActive() && user.isEmailVerified() && user.getEmail() != null
                && user.getPasswordHash() == null
                && social.existsByUserIdAndProvider(user.getId(), SocialLoginProvider.KAKAO);
    }

    private LocalDateTime databaseNow() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp", (row, index) -> row.getObject(1, LocalDateTime.class));
        if (now == null) throw new ServiceUnavailableException("인증 처리 시간을 확인할 수 없습니다.");
        return now;
    }

    private String emailHash(String email) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("withdrawal-email-v1:" + email.trim().toLowerCase(java.util.Locale.ROOT))
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("이메일 인증을 처리할 수 없습니다.", failure);
        }
    }

    private BadRequestException invalidProof() { return new BadRequestException("만료되었거나 사용할 수 없는 이메일 인증입니다."); }
    private record Proof(AppUser user, WithdrawalEmailChallenge challenge) {}
    private static final class RecoveryPinMismatch extends BadCredentialsException {
        private RecoveryPinMismatch() { super("2차 비밀번호가 올바르지 않습니다."); }
    }
    private static final class RecoveryPinLimited extends TooManyRequestsException {
        private RecoveryPinLimited() { super("2차 비밀번호 확인 횟수를 초과했습니다. 15분 후 다시 시도해 주세요.", 900); }
    }
}
