package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.*;
import com.playdata.calen.travel.domain.policy.TravelPlanPolicy;
import com.playdata.calen.travel.dto.*;

public final class TravelClusterDetailMapper {
    private TravelClusterDetailMapper() {}
    public static Media media(TravelMediaAsset mediaAsset) {
        var record = mediaAsset.getRecord();
        var plan = mediaAsset.getPlan();
        String colorHex = TravelPlanPolicy.color(plan.getColorHex());
        return new Media(
                mediaAsset.getId(),
                plan.getId(),
                plan.getName(),
                colorHex,
                record.getId(),
                record.getRecordType(),
                mediaAsset.getMediaType(),
                mediaAsset.getOriginalFileName(),
                mediaAsset.getContentType(),
                mediaAsset.getFileSize(),
                mediaAsset.getCaption(),
                mediaAsset.getUploadedBy().getDisplayName(),
                mediaAsset.getUploadedAt(),
                record.getExpenseDate(),
                record.getExpenseTime(),
                record.getTitle(),
                record.getAmount(),
                record.getCurrencyCode(),
                record.getAmountKrw(),
                record.getCountry(),
                record.getRegion(),
                record.getPlaceName(),
                record.getLatitude(),
                record.getLongitude(),
                mediaAsset.getGpsLatitude(),
                mediaAsset.getGpsLongitude(),
                Boolean.TRUE.equals(mediaAsset.getRepresentativeOverride())
        );
    }
    public static TravelMediaResponse owned(Media value) {
        return value == null ? null : response(value, "/api/travel/media/" + value.id() + "/content");
    }
    public static TravelMediaResponse response(Media v, String contentUrl) {
        return new TravelMediaResponse(v.id(), v.planId(), v.planName(), v.planColorHex(), v.recordId(),
                v.recordType(), v.mediaType(), v.originalFileName(), v.contentType(), v.fileSize(), v.caption(),
                v.uploadedBy(), v.uploadedAt(), contentUrl, v.expenseDate(), v.expenseTime(), v.title(),
                v.amount(), v.currencyCode(), v.amountKrw(), v.country(), v.region(), v.placeName(),
                v.latitude(), v.longitude(), v.gpsLatitude(), v.gpsLongitude(), v.representativeOverride());
    }
    public static TravelMyMapPhotoClusterPageResponse response(Page page) {
        var h = page.cluster();
        return new TravelMyMapPhotoClusterPageResponse(h.id(), h.representativeMediaId(), h.representativeRecordId(),
                h.latitude(), h.longitude(), h.photoCount(), h.memoryCount(), h.maxDistanceMeters(),
                h.representativeOverride(), owned(h.representativePhoto()), page.photos().stream().map(TravelClusterDetailMapper::owned).toList(),
                page.page(), page.size(), page.totalPhotoCount(), page.hasNext());
    }
}
