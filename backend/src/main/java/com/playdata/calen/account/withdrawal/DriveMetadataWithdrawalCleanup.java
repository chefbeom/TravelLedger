package com.playdata.calen.account.withdrawal;

import com.playdata.calen.common.exception.BadRequestException;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Removes owned database metadata only after the corresponding inventory is durably queued. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class DriveMetadataWithdrawalCleanup {
    private final DriveWithdrawalInventory inventory;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void captureAndRemove(Long ownerId, UUID requestId) {
        // Capturing also locks and freshly validates the account and withdrawal request.
        // A rollback restores both inventory tasks and all source metadata.
        var summary = inventory.capture(ownerId, requestId);
        Long conflictingLinks = entities.createQuery("""
                select count(link) from DriveDownloadLink link
                where (link.owner.id = :owner or link.item.owner.id = :owner)
                  and link.owner.id <> link.item.owner.id
                """, Long.class).setParameter("owner", ownerId).getSingleResult();
        Long remainingShares = entities.createQuery("""
                select count(share) from DriveShare share
                where share.owner.id = :owner or share.item.owner.id = :owner
                """, Long.class).setParameter("owner", ownerId).getSingleResult();
        if (summary.invalidPath() || conflictingLinks != 0 || remainingShares != 0) {
            throw new BadRequestException("드라이브 파일 또는 공유 연결을 안전하게 정리할 수 없습니다.");
        }

        // Audit logs were already anonymized separately; these are the revoked link rows,
        // not other users' files or independent copies. No storage I/O is performed here.
        entities.flush();
        entities.createQuery("delete from DriveDownloadLink link where link.owner.id = :owner")
                .setParameter("owner", ownerId).executeUpdate();
        entities.createQuery("delete from DriveItemVersion version where version.owner.id = :owner")
                .setParameter("owner", ownerId).executeUpdate();
        entities.createQuery("delete from DriveUploadGrant grant where grant.ownerId = :owner")
                .setParameter("owner", ownerId).executeUpdate();
        // Break only this owner's parent edges; inventory rejected cross-owner edges above.
        entities.createQuery("update DriveItem item set item.parent = null where item.owner.id = :owner and item.parent is not null")
                .setParameter("owner", ownerId).executeUpdate();
        entities.createQuery("delete from DriveItem item where item.owner.id = :owner")
                .setParameter("owner", ownerId).executeUpdate();
    }
}
