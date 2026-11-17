package com.playdata.calen.travel.service;

import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.storage.SignedUploadWindow;
import com.playdata.calen.travel.domain.TravelUploadGrant;
import com.playdata.calen.travel.domain.TravelUploadGrant.Kind;
import com.playdata.calen.travel.domain.model.TravelMediaData;
import com.playdata.calen.travel.domain.model.TravelRouteUploadData;
import com.playdata.calen.travel.repository.TravelUploadGrantRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TravelUploadGrantRegistry {
    private final TravelUploadGrantRepository grants;
    private final MinioProperties minio;
    @Value("${app.travel.media-object-prefix:travel-media}") private String configuredPrefix;

    /** Called inside the transaction that holds the uploader's account lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordMedia(Long owner, Long plan, Long record, List<TravelMediaData.Target> uploads) {
        if (uploads == null || uploads.isEmpty()) throw invalid();
        String prefix = prefix() + "/";
        for (var file : uploads) {
            if (file == null || !TravelUploadGrantPaths.isMedia(owner, plan, record, file.objectKey())
                    || !file.objectKey().startsWith(prefix)) throw invalid();
            save(owner, plan, record, Kind.MEDIA, file.objectKey(), null, file.method(), file.uploadUrl());
            for (var thumb : file.thumbnails() == null ? List.<TravelMediaData.ThumbnailTarget>of() : file.thumbnails()) {
                if (thumb == null || !TravelUploadGrantPaths.isThumbnail(owner, plan, record, file.objectKey(), thumb.objectKey())) throw invalid();
                save(owner, plan, record, Kind.THUMBNAIL, thumb.objectKey(), file.objectKey(), thumb.method(), thumb.uploadUrl());
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRoutes(Long owner, Long plan, Long route, List<TravelRouteUploadData.Target> uploads) {
        if (uploads == null || uploads.isEmpty()) throw invalid();
        String prefix = prefix() + "/";
        for (var file : uploads) {
            if (file == null || !TravelUploadGrantPaths.isRoute(owner, plan, route, file.objectKey())
                    || !file.objectKey().startsWith(prefix)) throw invalid();
            save(owner, plan, route, Kind.ROUTE_GPX, file.objectKey(), null, file.method(), file.uploadUrl());
        }
    }

    private void save(Long owner, Long plan, Long source, Kind kind, String key, String original, String method, String url) {
        String bucket = minio.getBucket_cloud();
        if (!"PUT".equals(method) || bucket == null || bucket.isBlank() || bucket.length() > 255
                || bucket.chars().anyMatch(Character::isISOControl)) throw invalid();
        var window = SignedUploadWindow.parse(url, minio.getPresignedUrlExpirySeconds());
        var grant = new TravelUploadGrant();
        grant.setOwnerId(owner); grant.setPlanId(plan); grant.setSourceId(source); grant.setKind(kind);
        grant.setObjectKey(key); grant.setOriginalKey(original); grant.setStorageBucket(bucket);
        grant.setSignedAtUtc(window.signedAtUtc()); grant.setExpiresAtUtc(window.expiresAtUtc());
        grants.save(grant);
    }

    private String prefix() {
        String value = configuredPrefix == null ? "" : configuredPrefix.trim().replace('\\', '/')
                .replaceAll("^/+|/+$", "").replaceAll("/+", "/");
        if (value.isEmpty()) value = "travel-media";
        for (String part : value.split("/")) if (part.equals(".") || part.equals("..")) throw invalid();
        if (value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }

    private ServiceUnavailableException invalid() {
        return new ServiceUnavailableException("여행 파일 업로드의 안전한 정리 정보를 확인할 수 없습니다.");
    }
}
