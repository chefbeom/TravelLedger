package com.playdata.calen.familyalbum.service;

import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.storage.SignedUploadWindow;
import com.playdata.calen.familyalbum.domain.FamilyMediaUploadGrant;
import com.playdata.calen.familyalbum.dto.FamilyMediaUploadTargetResponse;
import com.playdata.calen.familyalbum.repository.FamilyMediaUploadGrantRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FamilyMediaUploadGrantRegistry {
    private final FamilyMediaUploadGrantRepository grants;
    private final MinioProperties minio;
    @Value("${app.family.media-object-prefix:family-media}") private String configuredPrefix;

    /** The uploader is already locked by the surrounding family upload transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordIssued(Long ownerId, Long categoryId, List<FamilyMediaUploadTargetResponse> uploads) {
        String bucket = minio.getBucket_cloud();
        if (ownerId == null || ownerId <= 0 || categoryId == null || categoryId <= 0
                || bucket == null || bucket.isBlank() || bucket.length() > 255
                || bucket.chars().anyMatch(Character::isISOControl) || uploads == null || uploads.isEmpty()) throw invalid();
        String prefix = normalizedPrefix() + "/" + ownerId + "/" + categoryId + "/";
        for (var upload : uploads) {
            if (upload == null || !"PUT".equals(upload.method()) || upload.objectKey() == null
                    || upload.objectKey().length() > 600 || !upload.objectKey().startsWith(prefix)
                    || upload.objectKey().substring(prefix.length()).isBlank()
                    || upload.objectKey().substring(prefix.length()).contains("/")
                    || upload.objectKey().contains("\\") || upload.objectKey().chars().anyMatch(Character::isISOControl)) throw invalid();
            var signed = SignedUploadWindow.parse(upload.uploadUrl(), minio.getPresignedUrlExpirySeconds());
            var grant = new FamilyMediaUploadGrant();
            grant.setOwnerId(ownerId); grant.setCategoryId(categoryId); grant.setObjectKey(upload.objectKey());
            grant.setStorageBucket(bucket); grant.setSignedAtUtc(signed.signedAtUtc()); grant.setExpiresAtUtc(signed.expiresAtUtc());
            grants.save(grant);
        }
    }

    private String normalizedPrefix() {
        String prefix = configuredPrefix == null ? "" : configuredPrefix.trim().replace('\\', '/')
                .replaceAll("^/+|/+$", "").replaceAll("/+", "/");
        if (prefix.isEmpty()) prefix = "family-media";
        for (String part : prefix.split("/")) if (part.equals(".") || part.equals("..")) throw invalid();
        if (prefix.chars().anyMatch(Character::isISOControl)) throw invalid();
        return prefix;
    }

    private ServiceUnavailableException invalid() {
        return new ServiceUnavailableException("가족 사진 업로드의 안전한 정리 정보를 확인할 수 없습니다.");
    }
}
