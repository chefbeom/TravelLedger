package com.playdata.calen.travel.adapter.out.persistence;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.adapter.support.TravelClusterDetailMapper;
import com.playdata.calen.travel.application.port.out.TravelMediaStore;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.Media;
import com.playdata.calen.travel.domain.policy.TravelRecordPolicy;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.TravelDriveLinkService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaTravelMediaAdapter implements TravelMediaStore {
    private final AppUserService users;
    private final AppUserRepository accounts;
    private final TravelExpenseRecordRepository records;
    private final TravelMediaAssetRepository media;
    private final TravelDriveLinkService drive;
    @Override
    public void requireUser(Long owner) {
        users.getRequiredUser(owner);
    }

    @Override
    public Access ownedRecord(Long owner, Long id, TravelRecordType expected) {
        String message = expected == TravelRecordType.MEMORY ? "Travel memory not found." : "Travel ledger record not found.";
        var record = records.findByIdAndPlanOwnerId(id, owner).orElseThrow(() -> new NotFoundException(message));
        if (expected == TravelRecordType.MEMORY && !TravelRecordPolicy.matches(record.getRecordType(), TravelRecordType.MEMORY)) {
            throw new NotFoundException(message);
        }
        if (expected == TravelRecordType.LEDGER && !TravelRecordPolicy.matches(record.getRecordType(), TravelRecordType.LEDGER)) {
            throw new NotFoundException(message);
        }
        return new Access(record.getId(), record.getPlan().getId());
    }

    @Override
    public Long save(Long owner, Long recordId, TravelMediaType type, String caption, Stored stored, Gps gps) {
        var record = records.getReferenceById(recordId);
        var asset = new TravelMediaAsset();
        asset.setPlan(record.getPlan());
        asset.setRecord(record);
        asset.setUploadedBy(accounts.getReferenceById(owner));
        asset.setMediaType(type != null ? type : TravelMediaType.PHOTO);
        asset.setOriginalFileName(stored.originalFileName());
        asset.setStoredFileName(stored.storedFileName());
        asset.setStoragePath(stored.storagePath());
        asset.setContentType(stored.contentType());
        asset.setFileSize(stored.fileSize());
        asset.setCaption(caption);
        if (gps != null) {
            asset.setGpsLatitude(gps.latitude());
            asset.setGpsLongitude(gps.longitude());
            asset.setGpsExtractedAt(LocalDateTime.now());
        }
        return media.save(asset).getId();
    }

    @Override
    public void link(List<Long> ids) {
        ids.forEach(id -> drive.linkMediaAsset(media.getReferenceById(id)));
    }

    @Override
    public List<Media> media(List<Long> ids) {
        return ids.stream().map(media::getReferenceById).map(TravelClusterDetailMapper::media).toList();
    }

    @Override
    public Owned ownedMedia(Long owner, Long id) {
        var asset = owned(owner, id);
        boolean cluster = asset.getMediaType() == TravelMediaType.PHOTO
                && TravelRecordPolicy.matches(asset.getRecord().getRecordType(), TravelRecordType.MEMORY);
        return new Owned(asset.getId(), download(asset), cluster);
    }

    @Override
    public Download download(Long owner, Long id) {
        return download(owned(owner, id));
    }

    @Override
    public void removeLink(Long id) {
        drive.removeMediaLink(media.getReferenceById(id));
    }

    @Override
    public void delete(Long id) {
        media.delete(media.getReferenceById(id));
    }

    private TravelMediaAsset owned(Long owner, Long id) {
        return media.findByIdAndPlanOwnerId(id, owner).orElseThrow(() -> new NotFoundException("Uploaded file not found."));
    }

    private Download download(TravelMediaAsset asset) {
        return new Download(asset.getStoragePath(), asset.getContentType(), asset.getOriginalFileName());
    }
}
