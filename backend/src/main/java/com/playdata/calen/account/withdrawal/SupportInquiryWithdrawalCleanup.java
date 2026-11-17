package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal stage: remove only the sender's inquiries and queue exact attachment paths. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class SupportInquiryWithdrawalCleanup {
    private static final int PAGE_SIZE = 100;
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskStore tasks;
    private final EntityManager entities;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.MANDATORY)
    public void scheduleAndRemove(Long userId, UUID requestId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(userId).orElse(null);
        if (user.isActive() || request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || requestId == null || !requestId.toString().equals(request.getId())) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        long cursor = 0L;
        while (true) {
            List<Object[]> rows = entities.createQuery("""
                    select inquiry.id, inquiry.attachmentStoragePath, inquiry.attachmentStorageId, inquiry.attachmentSha256 from SupportInquiry inquiry
                    where inquiry.sender.id = :owner and inquiry.id > :cursor order by inquiry.id
                    """, Object[].class).setParameter("owner", userId).setParameter("cursor", cursor)
                    .setMaxResults(PAGE_SIZE).getResultList();
            if (rows.isEmpty()) break;
            for (Object[] row : rows) {
                cursor = (Long) row[0];
                String path = (String) row[1];
                if (path == null || path.isEmpty()) continue;
                validatePath(userId, path);
                Long shared = entities.createQuery("""
                        select count(inquiry) from SupportInquiry inquiry
                        where inquiry.sender.id <> :owner and inquiry.attachmentStoragePath = :path
                        """, Long.class).setParameter("owner", userId).setParameter("path", path).getSingleResult();
                if (shared != 0L) throw new BadRequestException("다른 사용자가 참조하는 문의 첨부파일이 있어 탈퇴를 진행할 수 없습니다.");
                // Legacy paths lack provenance. They remain as non-executable tasks for
                // operator reconciliation rather than guessing where the object resides.
                String storageId = (String) row[2], digest = (String) row[3];
                String payload = path;
                if (storageId != null || digest != null) {
                    try { payload = json.writeValueAsString(new SupportAttachmentTarget(userId, path, storageId, digest)); }
                    catch (JsonProcessingException exception) {
                        throw new ServiceUnavailableException("문의 첨부파일 정리 정보를 안전하게 저장할 수 없습니다.");
                    }
                }
                tasks.enqueue(requestId, WithdrawalCleanupKind.SUPPORT_ATTACHMENT, "support:" + path, payload);
            }
        }
        entities.createQuery("delete from SupportInquiry inquiry where inquiry.sender.id = :owner")
                .setParameter("owner", userId).executeUpdate();
        // Keep other users' inquiries and replies, but detach the departing admin identity.
        entities.createQuery("update SupportInquiry inquiry set inquiry.repliedBy = null where inquiry.repliedBy.id = :owner")
                .setParameter("owner", userId).executeUpdate();
    }

    private void validatePath(Long userId, String path) {
        if (!SupportAttachmentTarget.validPath(userId, path)) {
            throw new BadRequestException("문의 첨부파일 경로를 안전하게 확인할 수 없습니다.");
        }
    }
}
