package com.playdata.calen.account.withdrawal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.familyalbum.domain.FamilyMediaUploadGrant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Captures upload locations only; an album photo may already be owned by another member. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class FamilyUploadWithdrawalInventory {
    private static final int PAGE_SIZE = 100;
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskStore tasks;
    private final EntityManager entities;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.MANDATORY)
    public void captureAndRemove(Long ownerId, UUID requestId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(ownerId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(ownerId).orElse(null);
        if (user.isActive() || request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || requestId == null || !requestId.toString().equals(request.getId())) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        long cursor = 0;
        while (true) {
            List<FamilyMediaUploadGrant> rows = entities.createQuery("""
                    select g from FamilyMediaUploadGrant g where g.ownerId = :owner and g.id > :cursor order by g.id
                    """, FamilyMediaUploadGrant.class).setParameter("owner", ownerId).setParameter("cursor", cursor)
                    .setMaxResults(PAGE_SIZE).getResultList();
            if (rows.isEmpty()) break;
            for (var grant : rows) {
                cursor = grant.getId();
                validate(ownerId, grant);
                try {
                    var target = new UploadGrant(ownerId, cursor, grant.getCategoryId(), grant.getObjectKey(),
                            grant.getStorageBucket(), grant.getSignedAtUtc(), grant.getExpiresAtUtc());
                    tasks.enqueue(requestId, WithdrawalCleanupKind.FAMILY_UPLOAD_GRANT_INVENTORY,
                            "family-upload-grant:" + cursor, json.writeValueAsString(target));
                } catch (JsonProcessingException failure) {
                    throw new ServiceUnavailableException("가족 사진 업로드 정리 정보를 안전하게 저장할 수 없습니다.");
                }
            }
        }
        entities.createQuery("delete from FamilyMediaUploadGrant g where g.ownerId = :owner")
                .setParameter("owner", ownerId).executeUpdate();
    }

    private void validate(Long ownerId, FamilyMediaUploadGrant grant) {
        String key = grant.getObjectKey();
        String bucket = grant.getStorageBucket();
        if (grant.getCategoryId() == null || grant.getCategoryId() <= 0 || key == null || key.isBlank()
                || key.chars().anyMatch(Character::isISOControl) || key.contains("\\")
                || bucket == null || bucket.isBlank() || bucket.chars().anyMatch(Character::isISOControl)
                || grant.getSignedAtUtc() == null || grant.getExpiresAtUtc() == null) throw invalid();
        String marker = "/" + ownerId + "/" + grant.getCategoryId() + "/";
        int index = key.lastIndexOf(marker);
        String leaf = index < 0 ? "" : key.substring(index + marker.length());
        if (index <= 0 || leaf.isBlank() || leaf.contains("/")) throw invalid();
        String prefix = key.substring(0, index);
        for (String part : prefix.split("/")) if (part.isBlank() || part.equals(".") || part.equals("..")) throw invalid();
        Duration validity = Duration.between(grant.getSignedAtUtc(), grant.getExpiresAtUtc());
        if (validity.isNegative() || validity.isZero() || validity.compareTo(Duration.ofDays(7)) > 0) throw invalid();
    }

    private BadRequestException invalid() {
        return new BadRequestException("가족 사진 업로드의 정리 정보를 안전하게 확인할 수 없습니다.");
    }

    public record UploadGrant(Long ownerId, Long grantId, Long categoryId, String objectKey, String storageBucket,
                              LocalDateTime signedAtUtc, LocalDateTime expiresAtUtc) {
        @Override public String toString() { return "FamilyUploadGrant[redacted]"; }
    }
}
