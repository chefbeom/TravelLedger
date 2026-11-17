package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.account.social.KakaoOAuthProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Revalidates the durable task and account before a later, external provider call. */
@Service
@RequiredArgsConstructor
public class KakaoWithdrawalPreflight {
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskRepository tasks;
    private final AppUserRepository users;
    private final SocialAccountRepository socialAccounts;
    private final KakaoOAuthProperties kakaoProperties;
    private final WithdrawalPayloadCipher cipher;
    private final EntityManager entities;
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Result verify(WithdrawalCleanupTaskStore.Claim claim, Duration requiredTime) {
        if (claim == null || claim.requestId() == null || claim.taskId() == null || claim.token() == null
                || claim.kind() != WithdrawalCleanupKind.KAKAO_UNLINK || requiredTime == null
                || requiredTime.isNegative() || requiredTime.isZero() || requiredTime.compareTo(Duration.ofMinutes(4)) > 0) {
            return Result.LOST_LEASE;
        }
        var request = requests.findById(claim.requestId().toString()).orElse(null);
        var user = request == null || request.getUserId() == null ? null
                : users.lockForAccountSecurityUpdate(request.getUserId()).orElse(null);
        if (user != null) entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (request != null) entities.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        var task = tasks.lockById(claim.taskId().toString()).orElse(null);
        if (task == null) return Result.LOST_LEASE;
        entities.refresh(task, LockModeType.PESSIMISTIC_WRITE);
        LocalDateTime now = jdbc.queryForObject("select current_timestamp", (row, index) -> row.getObject(1, LocalDateTime.class));
        if (now == null) throw new IllegalStateException("Withdrawal database time unavailable.");
        if (task.getKind() != WithdrawalCleanupKind.KAKAO_UNLINK
                || !claim.requestId().toString().equals(task.getRequestId())
                || task.getState() != WithdrawalCleanupTask.State.PROCESSING
                || !claim.token().toString().equals(task.getClaimToken())
                || task.getLeaseUntil() == null || !task.getLeaseUntil().isAfter(now.plus(requiredTime))) {
            return Result.LOST_LEASE;
        }
        if (request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || user == null || user.isActive() || !user.getId().equals(request.getUserId())) return Result.MANUAL_REVIEW;
        String target;
        try {
            target = cipher.decrypt(claim.requestId(), claim.taskId(), task.getKind().name(), task.getEncryptedPayload());
        } catch (RuntimeException invalid) {
            return Result.MANUAL_REVIEW;
        }
        if (target == null || !target.matches("[1-9][0-9]{0,18}") || claim.payload() == null
                || !MessageDigest.isEqual(target.getBytes(StandardCharsets.UTF_8), claim.payload().getBytes(StandardCharsets.UTF_8))) {
            return Result.MANUAL_REVIEW;
        }
        Long appId = kakaoProperties.getExpectedAppId();
        if (appId == null || appId <= 0 || !appId.equals(kakaoProperties.getAdminAppId())) return Result.MANUAL_REVIEW;
        long matches = socialAccounts.findAllByUserIdAndProvider(user.getId(), SocialLoginProvider.KAKAO).stream()
                .filter(account -> target.equals(account.getProviderUserId()) && appId.equals(account.getProviderAppId())).count();
        return matches == 1 ? Result.READY : Result.MANUAL_REVIEW;
    }

    public enum Result { READY, MANUAL_REVIEW, LOST_LEASE }
}
