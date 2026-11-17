package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.LoginAuditLogRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded historical classification; known accounts and non-failure events keep their old detail. */
@Service
@RequiredArgsConstructor
public class UnknownLoginAuditPrivacyBackfill {
    private static final int MAX_BATCH = 250;
    private static final List<LoginAuditStatus> FAILURES = List.of(LoginAuditStatus.BAD_CREDENTIALS,
            LoginAuditStatus.BAD_SECONDARY_PIN, LoginAuditStatus.BLOCKED);
    private final LoginAuditLogRepository logs;
    private final AppUserRepository users;

    @Transactional
    public int classifyBatch(int limit) {
        if (limit < 1 || limit > MAX_BATCH) throw new IllegalArgumentException("Invalid audit batch size.");
        var batch = logs.findUnclassifiedUnknownFailures(FAILURES, PageRequest.of(0, limit));
        for (var log : batch) {
            String loginId = log.getLoginId() == null ? "" : log.getLoginId().trim();
            if (loginId.isEmpty() || !users.existsByLoginIdIgnoreCase(loginId)) {
                AuditLogAnonymization.anonymizeActor(log);
            }
            // Known accounts are marked classified too so they cannot starve later unknown records.
            log.setUnknownFailurePrivacyProcessed(true);
        }
        logs.flush();
        return batch.size();
    }
}
