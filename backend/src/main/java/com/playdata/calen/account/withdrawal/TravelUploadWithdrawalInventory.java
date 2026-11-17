package com.playdata.calen.account.withdrawal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.travel.domain.TravelUploadGrant;
import com.playdata.calen.travel.service.TravelUploadGrantPaths;
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

/** Inventory only: neither shared plans nor underlying storage objects are deleted here. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class TravelUploadWithdrawalInventory {
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
            List<TravelUploadGrant> rows = entities.createQuery("""
                    select g from TravelUploadGrant g where g.ownerId = :owner and g.id > :cursor order by g.id
                    """, TravelUploadGrant.class).setParameter("owner", ownerId).setParameter("cursor", cursor)
                    .setMaxResults(PAGE_SIZE).getResultList();
            if (rows.isEmpty()) break;
            for (var grant : rows) {
                cursor = grant.getId();
                validate(ownerId, grant);
                try {
                    var target = new UploadGrant(ownerId, cursor, grant.getPlanId(), grant.getSourceId(), grant.getKind(),
                            grant.getObjectKey(), grant.getOriginalKey(), grant.getStorageBucket(), grant.getSignedAtUtc(), grant.getExpiresAtUtc());
                    tasks.enqueue(requestId, WithdrawalCleanupKind.TRAVEL_UPLOAD_GRANT_INVENTORY,
                            "travel-upload-grant:" + cursor, json.writeValueAsString(target));
                } catch (JsonProcessingException failure) {
                    throw new ServiceUnavailableException("여행 파일 업로드 정리 정보를 안전하게 저장할 수 없습니다.");
                }
            }
        }
        entities.createQuery("delete from TravelUploadGrant g where g.ownerId = :owner")
                .setParameter("owner", ownerId).executeUpdate();
    }

    private void validate(Long owner, TravelUploadGrant grant) {
        String bucket = grant.getStorageBucket();
        if (grant.getKind() == null || bucket == null || bucket.isBlank() || bucket.length() > 255
                || bucket.chars().anyMatch(Character::isISOControl)
                || grant.getSignedAtUtc() == null || grant.getExpiresAtUtc() == null) throw invalid();
        boolean validPath = switch (grant.getKind()) {
            case MEDIA -> grant.getOriginalKey() == null && TravelUploadGrantPaths.isMedia(owner, grant.getPlanId(), grant.getSourceId(), grant.getObjectKey());
            case ROUTE_GPX -> grant.getOriginalKey() == null && TravelUploadGrantPaths.isRoute(owner, grant.getPlanId(), grant.getSourceId(), grant.getObjectKey());
            case THUMBNAIL -> TravelUploadGrantPaths.isThumbnail(owner, grant.getPlanId(), grant.getSourceId(), grant.getOriginalKey(), grant.getObjectKey());
        };
        Duration validity = Duration.between(grant.getSignedAtUtc(), grant.getExpiresAtUtc());
        if (!validPath || validity.isNegative() || validity.isZero() || validity.compareTo(Duration.ofDays(7)) > 0) throw invalid();
    }

    private BadRequestException invalid() {
        return new BadRequestException("여행 파일 업로드의 정리 정보를 안전하게 확인할 수 없습니다.");
    }

    public record UploadGrant(Long ownerId, Long grantId, Long planId, Long sourceId, TravelUploadGrant.Kind kind,
                              String objectKey, String originalKey, String storageBucket,
                              LocalDateTime signedAtUtc, LocalDateTime expiresAtUtc) {
        @Override public String toString() { return "TravelUploadGrant[redacted]"; }
    }
}
