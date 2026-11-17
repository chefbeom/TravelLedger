package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.domain.LoginAuditLog;
import com.playdata.calen.account.domain.LoginAuditStatus;
import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Scrubs known actor and subject identifiers in the caller's user-locked transaction. */
@Service
@RequiredArgsConstructor
public class AuditHistorySanitizer {
    private static final int PAGE_SIZE = 250;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void anonymize(Long userId, String loginId) {
        entities.flush();
        long lastId = 0L;
        while (true) {
            List<LoginAuditLog> batch = entities.createQuery("""
                    select log from LoginAuditLog log left join log.appUser actor
                    where log.id > :lastId and (actor.id = :userId
                      or (actor.id is null and lower(log.loginId) = lower(:loginId)))
                    order by log.id
                    """, LoginAuditLog.class)
                    .setParameter("lastId", lastId).setParameter("userId", userId)
                    .setParameter("loginId", loginId).setMaxResults(PAGE_SIZE).getResultList();
            if (batch.isEmpty()) break;
            lastId = batch.get(batch.size() - 1).getId();
            batch.forEach(AuditLogAnonymization::anonymizeActor);
            entities.flush();
            batch.forEach(entities::detach);
        }
        for (String type : List.of("ADMIN_ROLE_GRANT", "USER_ACTIVE_UPDATE", "DRIVE_USER_STATUS_UPDATE")) {
            List<String> details = "ADMIN_ROLE_GRANT".equals(type)
                    ? List.of(type + ":userId=" + userId)
                    : List.of(type + ":userId=" + userId + ",active=true", type + ":userId=" + userId + ",active=false");
            entities.createQuery("update LoginAuditLog log set log.detail = :type where log.status = :status and log.detail in :details")
                    .setParameter("type", type).setParameter("status", LoginAuditStatus.ADMIN_ACTION)
                    .setParameter("details", details).executeUpdate();
        }
    }
}
