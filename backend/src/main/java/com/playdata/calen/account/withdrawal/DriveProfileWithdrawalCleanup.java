package com.playdata.calen.account.withdrawal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Captures a profile image reference, but does not authorize or perform file deletion. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class DriveProfileWithdrawalCleanup {
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskStore tasks;
    private final EntityManager entities;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.MANDATORY)
    public void captureAndRemoveSettings(Long userId, UUID requestId) {
        entities.flush();
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new BadRequestException("탈퇴할 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        var request = requests.findByUserId(userId).orElse(null);
        if (user.isActive() || request == null || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS
                || requestId == null || !requestId.toString().equals(request.getId())) {
            throw new BadRequestException("탈퇴 처리 중인 계정만 정리할 수 있습니다.");
        }
        var settings = entities.createQuery("select s from DriveProfileSettings s where s.user.id = :owner",
                        com.playdata.calen.drive.domain.DriveProfileSettings.class)
                .setParameter("owner", userId).getResultList();
        for (var setting : settings) {
            String path = setting.getProfileImagePath();
            if (path != null && !path.isEmpty()) {
                // Older or unexpected paths require investigation, not a guessed deletion.
                if (!path.equals("drive-profile/" + userId + "/profile.png")) {
                    throw new BadRequestException("프로필 사진 경로를 안전하게 확인할 수 없습니다.");
                }
                try {
                    tasks.enqueue(requestId, WithdrawalCleanupKind.DRIVE_PROFILE_INVENTORY,
                            "drive-profile:" + path, json.writeValueAsString(new ProfileFile(userId, path, setting.getProfileImageBucket())));
                } catch (JsonProcessingException failure) {
                    throw new ServiceUnavailableException("프로필 사진 정리 정보를 안전하게 저장할 수 없습니다.");
                }
            }
            entities.remove(setting);
        }
    }

    public record ProfileFile(Long ownerId, String storagePath, String storageBucket) {
        @Override public String toString() { return "ProfileFile[redacted]"; }
    }
}
