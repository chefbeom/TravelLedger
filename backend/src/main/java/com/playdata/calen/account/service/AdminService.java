package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AccountInvite;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.domain.LoginAuditLog;
import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.account.dto.AdminBlockedIpResponse;
import com.playdata.calen.account.dto.AdminDashboardResponse;
import com.playdata.calen.account.dto.AdminInviteSummaryResponse;
import com.playdata.calen.account.dto.AdminLoginAuditPageResponse;
import com.playdata.calen.account.dto.AdminLoginAuditResponse;
import com.playdata.calen.account.dto.AdminSummaryResponse;
import com.playdata.calen.account.dto.AdminUserSummaryResponse;
import com.playdata.calen.account.repository.AccountInviteRepository;
import com.playdata.calen.account.repository.AccountEmailVerificationRepository;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.EmailVerificationTokenRepository;
import com.playdata.calen.account.withdrawal.PendingWithdrawalGuard;
import com.playdata.calen.account.withdrawal.WithdrawalChallengeRevoker;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import com.playdata.calen.account.security.AccountRememberMeTokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int RECENT_LOGIN_LOG_PAGE_SIZE = 10;

    private final AppUserRepository appUserRepository;
    private final AccountInviteRepository accountInviteRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final AccountEmailVerificationRepository accountEmailVerificationRepository;
    private final LoginAuditLogService loginAuditLogService;
    private final LoginAttemptService loginAttemptService;
    private final AccountRememberMeTokens persistentTokenRepository;
    private final ObjectProvider<PendingWithdrawalGuard> pendingWithdrawalGuard;
    private final WithdrawalChallengeRevoker withdrawalChallengeRevoker;
    private final AppUserService appUserService;
    private final EntityManager entities;

    public AdminDashboardResponse getDashboard() {
        List<AppUser> users = appUserRepository.findAllByOrderByIdAsc();
        Page<LoginAuditLog> recentLogs = loginAuditLogService.getRecentLogs(0, RECENT_LOGIN_LOG_PAGE_SIZE);
        List<LoginAttemptService.BlockedIpSnapshot> blockedIps = loginAttemptService.getBlockedIps();
        List<AccountInvite> recentInvites = accountInviteRepository.findTop30ByOrderByCreatedAtDescIdDesc();

        return new AdminDashboardResponse(
                new AdminSummaryResponse(
                        appUserRepository.count(),
                        appUserRepository.countByActiveTrue(),
                        appUserRepository.countByRole(AppUserRole.ADMIN),
                        accountInviteRepository.count(),
                        accountInviteRepository.countByUsedAtIsNullAndExpiresAtAfter(LocalDateTime.now()),
                        loginAuditLogService.countRecentFailures(),
                        blockedIps.size()
                ),
                recentLogs.getContent().stream().map(this::toLoginLogResponse).toList(),
                recentLogs.getTotalPages(),
                recentLogs.getTotalElements(),
                blockedIps.stream().map(this::toBlockedIpResponse).toList(),
                users.stream().map(this::toUserResponse).toList(),
                recentInvites.stream().map(this::toInviteResponse).toList()
        );
    }

    public AdminLoginAuditPageResponse getLoginAuditLogs(int page) {
        Page<LoginAuditLog> recentLogs = loginAuditLogService.getRecentLogs(page, RECENT_LOGIN_LOG_PAGE_SIZE);
        return new AdminLoginAuditPageResponse(
                recentLogs.getContent().stream().map(this::toLoginLogResponse).toList(),
                recentLogs.getNumber(),
                recentLogs.getSize(),
                recentLogs.getTotalElements(),
                recentLogs.getTotalPages()
        );
    }

    @Transactional
    public AdminUserSummaryResponse updateUserActive(Long currentAdminUserId, Long targetUserId, boolean active) {
        AppUser targetUser = appUserRepository.lockForAccountSecurityUpdate(targetUserId)
                .orElseThrow(() -> new BadRequestException("사용자를 찾을 수 없습니다."));
        entities.refresh(targetUser, LockModeType.PESSIMISTIC_WRITE);

        if (currentAdminUserId.equals(targetUserId) && !active) {
            throw new BadRequestException("본인 계정은 비활성화할 수 없습니다.");
        }

        AppUserRole role = targetUser.getRole() != null ? targetUser.getRole() : AppUserRole.USER;
        if (role == AppUserRole.ADMIN) {
            throw new BadRequestException("관리자 계정의 활성 상태는 변경할 수 없습니다.");
        }

        if (active && targetUser.isExpiredEmailSignup()) {
            throw new BadRequestException("만료된 가입 신청은 다시 활성화할 수 없습니다.");
        }
        if (active) pendingWithdrawalGuard.ifAvailable(guard -> guard.requireNoPendingWithdrawal(targetUserId));

        targetUser.setActive(active);
        if (!active) {
            LocalDateTime now = LocalDateTime.now();
            withdrawalChallengeRevoker.revoke(targetUserId, now);
            emailVerificationTokenRepository.invalidateOpenTokens(targetUserId, now);
            accountEmailVerificationRepository.invalidateOpenTokens(targetUserId, now);
            persistentTokenRepository.removeUserTokensById(targetUserId);
        }
        return toUserResponse(targetUser);
    }

    /** Granting admin access is a sensitive action: role change, token revocation and audit commit together. */
    @Transactional
    public AdminUserSummaryResponse promoteUser(Long currentAdminUserId, Long targetUserId,
            String secondaryPin, String clientIp, String userAgent) {
        if (currentAdminUserId == null || targetUserId == null || targetUserId <= 0 || currentAdminUserId.equals(targetUserId)) {
            throw new BadRequestException("관리자로 지정할 다른 사용자를 선택해 주세요.");
        }
        AppUser actor = appUserRepository.findActiveForPreferenceUpdate(currentAdminUserId)
                .orElseThrow(() -> new BadRequestException("관리자 계정을 확인할 수 없습니다."));
        entities.refresh(actor, LockModeType.PESSIMISTIC_WRITE);
        if (actor.getRole() != AppUserRole.ADMIN) throw new BadRequestException("관리자 권한이 필요합니다.");
        appUserService.ensureSecondaryPinMatches(actor, secondaryPin);
        AppUser target = appUserRepository.lockForAccountSecurityUpdate(targetUserId)
                .orElseThrow(() -> new BadRequestException("사용자를 찾을 수 없습니다."));
        entities.refresh(target, LockModeType.PESSIMISTIC_WRITE);
        if (!target.isActive()) throw new BadRequestException("활성 사용자만 관리자로 지정할 수 있습니다.");
        if (target.getRole() == AppUserRole.ADMIN) throw new BadRequestException("이미 관리자 권한이 있습니다.");
        pendingWithdrawalGuard.ifAvailable(guard -> guard.requireNoPendingWithdrawal(targetUserId));
        target.setRole(AppUserRole.ADMIN);
        persistentTokenRepository.removeUserTokensById(targetUserId);
        String safeIp = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
        if (safeIp.length() > 120) safeIp = safeIp.substring(0, 120);
        loginAuditLogService.record(actor.getLoginId(), safeIp, userAgent, LoginAuditStatus.ADMIN_ACTION,
                "ADMIN_ROLE_GRANT:userId=" + targetUserId, actor);
        return toUserResponse(target);
    }

    @Transactional
    public void clearBlockedIp(String clientIp) {
        loginAttemptService.clearFailures(clientIp);
    }

    private AdminLoginAuditResponse toLoginLogResponse(LoginAuditLog log) {
        AppUser appUser = log.getAppUser();
        AppUserRole role = appUser != null && appUser.getRole() != null ? appUser.getRole() : AppUserRole.USER;
        return new AdminLoginAuditResponse(
                log.getId(),
                formatDateTime(log.getAttemptedAt()),
                log.getLoginId(),
                log.getClientIp(),
                log.getUserAgent(),
                mapAdminStatus(log.getStatus()),
                log.getDetail(),
                log.isSuccess(),
                appUser != null ? appUser.getId() : null,
                appUser != null ? appUser.getDisplayName() : null,
                appUser != null && role.isAdmin()
        );
    }

    private AdminBlockedIpResponse toBlockedIpResponse(LoginAttemptService.BlockedIpSnapshot snapshot) {
        return new AdminBlockedIpResponse(
                snapshot.clientIp(),
                snapshot.failureCount(),
                snapshot.lockedUntil().toString()
        );
    }

    private AdminUserSummaryResponse toUserResponse(AppUser user) {
        AppUserRole role = user.getRole() != null ? user.getRole() : AppUserRole.USER;
        return new AdminUserSummaryResponse(
                user.getId(),
                user.getLoginId(),
                user.getDisplayName(),
                role.name(),
                role.isAdmin(),
                user.isActive()
        );
    }

    private AdminInviteSummaryResponse toInviteResponse(AccountInvite invite) {
        return new AdminInviteSummaryResponse(
                invite.getId(),
                formatDateTime(invite.getCreatedAt()),
                formatDateTime(invite.getExpiresAt()),
                formatDateTime(invite.getUsedAt()),
                invite.getUsedAt() != null ? "USED" : invite.getRevokedAt() != null ? "REVOKED"
                        : invite.getExpiresAt().isBefore(LocalDateTime.now()) ? "EXPIRED" : "PENDING",
                invite.getCreatedBy() != null ? invite.getCreatedBy().getLoginId() : null,
                invite.getCreatedBy() != null ? invite.getCreatedBy().getDisplayName() : null,
                invite.getUsedBy() != null ? invite.getUsedBy().getLoginId() : null,
                invite.getUsedBy() != null ? invite.getUsedBy().getDisplayName() : null
        );
    }

    private String formatDateTime(LocalDateTime value) {
        return value != null ? DATE_TIME_FORMATTER.format(value) : null;
    }

    private String mapAdminStatus(LoginAuditStatus status) {
        return switch (status) {
            case SUCCESS -> "SUCCESS";
            case ADMIN_ACTION -> "ADMIN_ACTION";
            case BLOCKED -> "BLOCKED";
            case BAD_CREDENTIALS, BAD_SECONDARY_PIN -> "FAILED";
        };
    }
}
