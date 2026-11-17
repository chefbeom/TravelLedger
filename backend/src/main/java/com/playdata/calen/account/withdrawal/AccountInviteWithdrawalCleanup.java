package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.domain.AccountInvite;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Preserve invite outcomes and other accounts, but detach the departed identities. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class AccountInviteWithdrawalCleanup {
    private static final int PAGE_SIZE = 100;
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    @Transactional(propagation = Propagation.MANDATORY)
    public void anonymize(Long ownerId, UUID requestId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(ownerId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(ownerId).orElse(null);
        if (user.isActive() || request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || requestId == null || !requestId.toString().equals(request.getId())) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        Long inconsistent = entities.createQuery("""
                select count(invite) from AccountInvite invite
                where invite.usedBy.id = :owner and invite.usedAt is null
                """, Long.class).setParameter("owner", ownerId).getSingleResult();
        if (inconsistent != 0L) throw new BadRequestException("사용 상태가 일치하지 않는 초대 기록이 있습니다.");
        LocalDateTime now = jdbc.queryForObject("select current_timestamp", (row, index) -> row.getObject(1, LocalDateTime.class));
        if (now == null) throw new IllegalStateException("Withdrawal database time unavailable.");
        long cursor = 0;
        while (true) {
            List<AccountInvite> rows = entities.createQuery("""
                    select invite from AccountInvite invite
                    where invite.createdBy.id = :owner and invite.id > :cursor order by invite.id
                    """, AccountInvite.class).setParameter("owner", ownerId).setParameter("cursor", cursor)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).setMaxResults(PAGE_SIZE).getResultList();
            if (rows.isEmpty()) break;
            for (var invite : rows) {
                cursor = invite.getId();
                if (invite.getExpiresAt() == null) throw new BadRequestException("초대 기록의 만료 시각을 확인할 수 없습니다.");
                forgetToken(invite);
                if (invite.getUsedAt() == null && invite.getRevokedAt() == null) invite.setRevokedAt(now);
                invite.setCreatedBy(null);
            }
            entities.flush();
        }
        cursor = 0;
        while (true) {
            List<AccountInvite> rows = entities.createQuery("""
                    select invite from AccountInvite invite
                    where invite.usedBy.id = :owner and invite.id > :cursor order by invite.id
                    """, AccountInvite.class).setParameter("owner", ownerId).setParameter("cursor", cursor)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).setMaxResults(PAGE_SIZE).getResultList();
            if (rows.isEmpty()) break;
            for (var invite : rows) {
                cursor = invite.getId();
                if (invite.getUsedAt() == null) throw new BadRequestException("사용 상태가 일치하지 않는 초대 기록이 있습니다.");
                forgetToken(invite);
                invite.setUsedBy(null);
            }
            entities.flush();
        }
    }

    private void forgetToken(AccountInvite invite) {
        byte[] replacement = new byte[32]; random.nextBytes(replacement);
        invite.setTokenHash(HexFormat.of().formatHex(replacement));
    }
}
