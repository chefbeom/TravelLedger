package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.LoginAuditLog;
import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.LoginAuditLogRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoginAuditLogService {

    private final LoginAuditLogRepository loginAuditLogRepository;
    private final AppUserRepository appUserRepository;

    @Transactional
    public void record(
            String loginId,
            String clientIp,
            String userAgent,
            LoginAuditStatus status,
            String detail,
            AppUser appUser
    ) {
        LoginAuditLog log = new LoginAuditLog();
        log.setLoginId(loginId);
        log.setClientIp(clientIp);
        log.setUserAgent(limit(userAgent));
        log.setStatus(status);
        log.setSuccess(status.isSuccess());
        log.setDetail(limit(detail));
        log.setAppUser(appUser);
        loginAuditLogRepository.save(log);
    }

    @Transactional
    public void recordAdminAction(
            Long adminUserId,
            String loginId,
            String clientIp,
            String userAgent,
            String detail
    ) {
        AppUser adminUser = adminUserId != null
                ? appUserRepository.findById(adminUserId).orElse(null)
                : null;
        record(
                hasText(loginId) ? loginId : "unknown-admin",
                hasText(clientIp) ? clientIp : "unknown",
                userAgent,
                LoginAuditStatus.ADMIN_ACTION,
                detail,
                adminUser
        );
    }

    public Page<LoginAuditLog> getRecentLogs(int page, int size) {
        return loginAuditLogRepository.findAllByOrderByAttemptedAtDescIdDesc(PageRequest.of(Math.max(page, 0), Math.max(size, 1)));
    }

    public long countRecentFailures() {
        return loginAuditLogRepository.countBySuccessFalseAndAttemptedAtAfter(LocalDateTime.now().minusHours(24));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String limit(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 255 ? trimmed : trimmed.substring(0, 255);
    }
}
