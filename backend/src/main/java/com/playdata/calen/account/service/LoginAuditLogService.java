package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.LoginAuditLog;
import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.account.repository.LoginAuditLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoginAuditLogService {

    private final LoginAuditLogRepository loginAuditLogRepository;

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
        log.setUserAgent(userAgent);
        log.setStatus(status);
        log.setSuccess(status.isSuccess());
        log.setDetail(detail);
        log.setAppUser(appUser);
        loginAuditLogRepository.save(log);
    }

    public List<LoginAuditLog> getRecentLogs() {
        return loginAuditLogRepository.findTop100ByOrderByAttemptedAtDescIdDesc();
    }

    public long countRecentFailures() {
        return loginAuditLogRepository.countBySuccessFalseAndAttemptedAtAfter(LocalDateTime.now().minusHours(24));
    }
}
