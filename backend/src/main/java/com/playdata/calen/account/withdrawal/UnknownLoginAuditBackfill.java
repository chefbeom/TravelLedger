package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.domain.LoginAuditLog;
import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Test-only, bounded historical backfill. No startup job or public endpoint invokes it. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class UnknownLoginAuditBackfill {
    private static final int MAX_BATCH = 250;
    private final EntityManager entities;

    /** Each call is one transaction; callers can persist the returned cursor between batches. */
    @Transactional
    public Batch anonymizeBatch(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > MAX_BATCH) {
            throw new BadRequestException("감사 기록 정리 범위가 올바르지 않습니다.");
        }
        List<LoginAuditLog> batch = entities.createQuery("""
                select log from LoginAuditLog log
                where log.id > :afterId and log.appUser is null and log.status in :failures
                  and (log.loginId <> :anonymous or log.clientIp <> :anonymous
                       or log.userAgent is not null or log.detail is not null)
                  and not exists (select candidate.id from AppUser candidate
                                  where lower(candidate.loginId) = lower(trim(log.loginId)))
                order by log.id
                """, LoginAuditLog.class)
                .setParameter("afterId", afterId)
                .setParameter("anonymous", AuditLogAnonymization.ANONYMOUS)
                .setParameter("failures", List.of(LoginAuditStatus.BAD_CREDENTIALS,
                        LoginAuditStatus.BAD_SECONDARY_PIN, LoginAuditStatus.BLOCKED))
                .setMaxResults(limit).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        batch.forEach(AuditLogAnonymization::anonymizeActor);
        entities.flush();
        return new Batch(batch.isEmpty() ? afterId : batch.get(batch.size() - 1).getId(), batch.size());
    }

    public record Batch(long lastId, int anonymizedCount) {}
}
