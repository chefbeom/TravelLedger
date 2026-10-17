package com.playdata.calen.travel.application.service;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.application.port.in.ManageOwnedTravelMediaUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.Media;
import com.playdata.calen.travel.domain.policy.TravelMediaPolicy;
import java.util.*;

public final class ManageOwnedTravelMediaService implements ManageOwnedTravelMediaUseCase {
    private final TravelMediaStore media;
    private final TravelMediaStoragePort storage;
    private final OwnedTravelMediaCachePort cache;
    private final TravelSummaryInvalidationPort summaries;
    private final TravelClusterRefreshPort clusters;
    public ManageOwnedTravelMediaService(TravelMediaStore media, TravelMediaStoragePort storage, OwnedTravelMediaCachePort cache,
            TravelSummaryInvalidationPort summaries, TravelClusterRefreshPort clusters) {
        this.media = media;
        this.storage = storage;
        this.cache = cache;
        this.summaries = summaries;
        this.clusters = clusters;
    }

    @Override
    public Prepared prepare(Long owner, Long record, TravelRecordType expected, List<Candidate> files) {
        var access = media.ownedRecord(owner, record, expected);
        if (!storage.supportsPresigned()) {
            throw new BadRequestException("Travel media uploads require presigned object storage.");
        }
        return new Prepared("PRESIGNED", storage.prepare(owner, access.planId(), access.recordId(), files));
    }

    @Override
    public List<Media> complete(Long owner, Long record, TravelRecordType expected, TravelMediaType requested,
            String caption, List<Completed> files) {
        // Keep the legacy complete/prepare difference in validation order.
        if (!storage.supportsPresigned()) {
            throw new BadRequestException("MinIO presigned upload is not available.");
        }
        media.requireUser(owner);
        var access = media.ownedRecord(owner, record, expected);
        var type = TravelMediaPolicy.type(expected, requested);
        List<Long> saved = new ArrayList<>();
        for (var file : files) {
            var stored = storage.complete(owner, access.planId(), access.recordId(), file);
            var gps = TravelMediaPolicy.gps(file.gpsLatitude(), file.gpsLongitude());
            TravelMediaPolicy.requireStoredType(type, stored.contentType());
            saved.add(media.save(owner, access.recordId(), type, TravelMediaPolicy.caption(caption), stored, gps));
        }
        media.link(saved);
        var result = media.media(saved);
        if (!result.isEmpty()) {
            summaries.invalidateOwner(owner);
            if (expected == TravelRecordType.MEMORY && type == TravelMediaType.PHOTO) {
                clusters.refreshAfterChange(owner);
            }
        }
        return result;
    }

    @Override
    public Download download(Long owner, Long id) {
        var cached = cache.get(owner, id);
        if (cached != null) {
            return cached;
        }
        var result = media.download(owner, id);
        cache.put(owner, id, result);
        return result;
    }

    @Override
    public void delete(Long owner, Long id) {
        var access = media.ownedMedia(owner, id);
        media.removeLink(id);
        storage.delete(access.download().storagePath(), access.download().contentType());
        media.delete(id);
        cache.invalidate(owner, id);
        summaries.invalidateOwner(owner);
        if (access.affectsPhotoCluster()) {
            clusters.refreshAfterChange(owner);
        }
    }
}
