package com.playdata.calen.recordmap.service;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.recordmap.domain.*;
import com.playdata.calen.recordmap.dto.RecordMapDtos.PhotoView;
import com.playdata.calen.recordmap.repository.*;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RecordMapMediaService {
    private final PlaceVisitRepository visits;
    private final PlacePhotoRepository photos;
    private final RecordPlaceRepository places;
    private final TravelMediaAssetRepository travelPhotos;
    private final TravelMediaStorageService storage;
    private final RecordMapShareService sharing;

    @Transactional
    public PhotoView upload(Long owner, Long visitId, MultipartFile file) {
        PlaceVisit visit = visits.lockOwned(visitId, owner).orElseThrow(RecordMapShareService::unavailable);
        if (photos.countByVisitIdAndOwnerId(visitId, owner) >= 20) throw new BadRequestException("방문 기록당 사진은 최대 20장입니다.");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("");
        if (name.length() > 220 || !name.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png|webp|gif|bmp)$")) {
            throw new BadRequestException("지원하는 사진 파일을 선택해 주세요.");
        }
        // Zero is an isolated non-travel storage namespace; genuine travel plan IDs are positive.
        var stored = storage.store(owner, 0L, visitId, file);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) storage.deleteImageWithThumbnailsQuietly(stored.storagePath(), stored.contentType());
            }
        });
        if (!stored.contentType().startsWith("image/")) throw new BadRequestException("사진 파일만 등록할 수 있습니다.");
        PlacePhoto photo = new PlacePhoto();
        photo.setOwnerId(owner); photo.setVisit(visit); photo.setOriginalName(stored.originalFileName());
        photo.setStoragePath(stored.storagePath()); photo.setContentType(stored.contentType()); photo.setFileSize(stored.fileSize());
        photos.save(photo);
        return new PhotoView("photo:" + photo.getId(), photo.getOriginalName(), "/api/record-map/photos/" + photo.getId() + "/content");
    }

    @Transactional
    public void delete(Long owner, Long photoId) {
        PlacePhoto photo = photos.findByIdAndOwnerId(photoId, owner).orElseThrow(RecordMapShareService::unavailable);
        deleteRows(List.of(photo));
    }

    public void deleteRows(List<PlacePhoto> items) {
        photos.deleteAll(items);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                items.forEach(p -> storage.deleteImageWithThumbnailsQuietly(p.getStoragePath(), p.getContentType()));
            }
        });
    }

    public Media getOwned(Long owner, Long photoId) {
        PlacePhoto photo = photos.findByIdAndOwnerId(photoId, owner).orElseThrow(RecordMapShareService::unavailable);
        return new Media(photo.getStoragePath(), photo.getContentType(), photo.getOriginalName());
    }

    public Media getShared(String token, Long photoId) {
        RecordMapShare share = sharing.resolve(token);
        PlacePhoto photo = photos.findByIdAndOwnerId(photoId, share.getOwnerId()).orElseThrow(RecordMapShareService::unavailable);
        sharing.requireMediaScope(share, photo.getVisit().getPlace());
        return new Media(photo.getStoragePath(), photo.getContentType(), photo.getOriginalName());
    }

    public Media getSharedTravel(String token, Long mediaId) {
        RecordMapShare share = sharing.resolve(token);
        var photo = travelPhotos.findById(mediaId).filter(p -> Objects.equals(p.getPlan().getOwner().getId(), share.getOwnerId()))
            .filter(p -> p.getContentType().startsWith("image/")).orElseThrow(RecordMapShareService::unavailable);
        RecordPlace place = places.findByOwnerIdAndSourceTravelRecordId(share.getOwnerId(), photo.getRecord().getId())
            .orElseThrow(RecordMapShareService::unavailable);
        sharing.requireMediaScope(share, place);
        return new Media(photo.getStoragePath(), photo.getContentType(), photo.getOriginalFileName());
    }
    public record Media(String path, String contentType, String fileName) {}
}
