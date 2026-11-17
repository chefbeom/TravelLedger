package com.playdata.calen.drive.service;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.storage.SignedUploadWindow;
import com.playdata.calen.drive.domain.DriveUploadGrant;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveUploadGrantRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records grants in the issuance transaction before a client can receive the URL. */
@Service
@RequiredArgsConstructor
public class DriveUploadGrantRegistry {
    private final DriveUploadGrantRepository grants;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordIssued(Long ownerId, String bucket, List<DriveDtos.UploadChunkResponse> responses) {
        if (ownerId == null || ownerId <= 0 || bucket == null || bucket.isBlank() || bucket.length() > 255
                || bucket.chars().anyMatch(Character::isISOControl) || responses == null || responses.isEmpty()) throw invalid();
        for (var response : responses) {
            if (response == null || Boolean.TRUE.equals(response.partitioned()) || response.objectKey() == null
                    || !response.objectKey().equals(response.finalObjectKey()) || response.objectKey().length() > 600
                    || !response.objectKey().startsWith("drive/" + ownerId + "/")
                    || response.objectKey().substring(("drive/" + ownerId + "/").length()).isBlank()
                    || response.objectKey().substring(("drive/" + ownerId + "/").length()).contains("/")
                    || response.objectKey().contains("\\")
                    || response.objectKey().chars().anyMatch(Character::isISOControl)) throw invalid();
            var signed = SignedUploadWindow.parse(response.presignedUploadUrl(), response.presignedUrlExpiresIn());
            var grant = new DriveUploadGrant();
            grant.setOwnerId(ownerId); grant.setObjectKey(response.objectKey()); grant.setStorageBucket(bucket);
            grant.setSignedAtUtc(signed.signedAtUtc()); grant.setExpiresAtUtc(signed.expiresAtUtc());
            grants.save(grant);
        }
    }

    private static ServiceUnavailableException invalid() {
        return new ServiceUnavailableException("업로드 주소의 안전한 만료 정보를 확인할 수 없습니다.");
    }

}
