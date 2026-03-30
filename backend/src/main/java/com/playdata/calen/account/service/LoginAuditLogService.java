package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.LoginAuditLog;
import com.playdata.calen.account.domain.LoginAuditStatus;
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

    public Page<LoginAuditLog> getRecentLogs(int page, int size) {
        return loginAuditLogRepository.findAllByOrderByAttemptedAtDescIdDesc(PageRequest.of(Math.max(page, 0), Math.max(size, 1)));
    }

    public long countRecentFailures() {
        return loginAuditLogRepository.countBySuccessFalseAndAttemptedAtAfter(LocalDateTime.now().minusHours(24));
    }
}
