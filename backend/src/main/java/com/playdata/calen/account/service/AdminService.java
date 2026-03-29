package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AccountInvite;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.domain.LoginAuditLog;
import com.playdata.calen.account.dto.AdminBlockedIpResponse;
import com.playdata.calen.account.dto.AdminDashboardResponse;
import com.playdata.calen.account.dto.AdminInviteSummaryResponse;
import com.playdata.calen.account.dto.AdminLoginAuditResponse;
import com.playdata.calen.account.dto.AdminSummaryResponse;
import com.playdata.calen.account.dto.AdminUserSummaryResponse;
import com.playdata.calen.account.repository.AccountInviteRepository;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final AppUserRepository appUserRepository;
    private final AccountInviteRepository accountInviteRepository;
    private final LoginAuditLogService loginAuditLogService;
    private final LoginAttemptService loginAttemptService;

    public AdminDashboardResponse getDashboard() {
        List<AppUser> users = appUserRepository.findAllByOrderByIdAsc();
        List<LoginAuditLog> recentLogs = loginAuditLogService.getRecentLogs();
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
                recentLogs.stream().map(this::toLoginLogResponse).toList(),
                blockedIps.stream().map(this::toBlockedIpResponse).toList(),
                users.stream().map(this::toUserResponse).toList(),
                recentInvites.stream().map(this::toInviteResponse).toList()
        );
    }

    @Transactional
    public AdminUserSummaryResponse updateUserActive(Long currentAdminUserId, Long targetUserId, boolean active) {
        AppUser targetUser = appUserRepository.findById(targetUserId)
                .orElseThrow(() -> new BadRequestException("대상 사용자를 찾을 수 없습니다."));

        if (currentAdminUserId.equals(targetUserId) && !active) {
            throw new BadRequestException("본인 계정은 비활성화할 수 없습니다.");
        }

        AppUserRole role = targetUser.getRole() != null ? targetUser.getRole() : AppUserRole.USER;
        if (role == AppUserRole.ADMIN && !active && appUserRepository.countByRoleAndActiveTrue(AppUserRole.ADMIN) <= 1) {
            throw new BadRequestException("마지막 관리자 계정은 비활성화할 수 없습니다.");
        }

        targetUser.setActive(active);
        return toUserResponse(targetUser);
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
                log.getStatus().name(),
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
                invite.getUsedAt() != null ? "USED" : invite.getExpiresAt().isBefore(LocalDateTime.now()) ? "EXPIRED" : "PENDING",
                invite.getCreatedBy().getLoginId(),
                invite.getCreatedBy().getDisplayName(),
                invite.getUsedBy() != null ? invite.getUsedBy().getLoginId() : null,
                invite.getUsedBy() != null ? invite.getUsedBy().getDisplayName() : null
        );
    }

    private String formatDateTime(LocalDateTime value) {
        return value != null ? DATE_TIME_FORMATTER.format(value) : null;
    }
}
