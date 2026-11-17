package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.LoginAuditStatus;
import java.util.List;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Serializes audit writes with account withdrawal and handles actors and known target IDs separately. */
@Service
@RequiredArgsConstructor
public class WithdrawalAuditWriteFence {
    private final JdbcTemplate jdbc;
    private final PendingWithdrawalGuard withdrawals;

    @Transactional(propagation = Propagation.MANDATORY)
    public Decision inspect(String loginId, AppUser actor, LoginAuditStatus status, String detail) {
        List<Long> actorIds = actor != null && actor.getId() != null ? List.of(actor.getId())
                : !StringUtils.hasText(loginId) ? List.of()
                : jdbc.queryForList("select id from app_users where lower(login_id) = lower(?) order by id",
                        Long.class, loginId.trim());
        Long targetId = AuditLogAnonymization.targetUserId(status, detail);
        var ids = new TreeSet<Long>(actorIds);
        if (targetId != null) ids.add(targetId);
        // Once an account is physically deleted, an old login ID is indistinguishable
        // from any other unknown ID. User policy anonymizes all such failure records.
        boolean actorWithdrawing = actor == null && actorIds.isEmpty()
                && (status == LoginAuditStatus.BAD_CREDENTIALS || status == LoginAuditStatus.BAD_SECONDARY_PIN
                    || status == LoginAuditStatus.BLOCKED);
        boolean targetWithdrawing = false;
        for (Long id : ids) {
            AccountState state = jdbc.query("select active, expired_email_signup from app_users where id = ? for update",
                    rows -> rows.next() ? new AccountState(rows.getBoolean(1), rows.getBoolean(2)) : null, id);
            // A stale in-flight principal or target may have been physically deleted already.
            if (state == null || state.expiredSignup()) {
                actorWithdrawing |= actorIds.contains(id);
                targetWithdrawing |= id.equals(targetId);
                continue;
            }
            if (state.active() || !withdrawals.hasPendingWithdrawalForUpdate(id)) continue;
            actorWithdrawing |= actorIds.contains(id);
            targetWithdrawing |= id.equals(targetId);
        }
        return new Decision(actorWithdrawing, targetWithdrawing);
    }

    public record Decision(boolean anonymizeActor, boolean anonymizeTarget) {}

    private record AccountState(boolean active, boolean expiredSignup) {}
}
