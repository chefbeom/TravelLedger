package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.PhotoPoint;
import java.math.BigDecimal;

public final class TravelClusterPhotoMapper {
    private TravelClusterPhotoMapper() {}
    public static BigDecimal latitude(TravelMediaAsset asset) {
        return asset.getGpsLatitude() != null ? asset.getGpsLatitude() : asset.getRecord().getLatitude();
    }
    public static BigDecimal longitude(TravelMediaAsset asset) {
        return asset.getGpsLongitude() != null ? asset.getGpsLongitude() : asset.getRecord().getLongitude();
    }
    public static boolean hasCoordinates(TravelMediaAsset asset) { return latitude(asset) != null && longitude(asset) != null; }
    public static PhotoPoint point(TravelMediaAsset asset) {
        var record = asset.getRecord(); var plan = record.getPlan();
        return new PhotoPoint(asset.getId(), record.getId(), plan.getId(), plan.getName(), plan.getColorHex(), record.getExpenseDate(),
                record.getExpenseTime(), record.getCategory(), record.getTitle(), record.getCountry(), record.getRegion(), record.getPlaceName(),
                latitude(asset), longitude(asset), asset.getUploadedAt(), Boolean.TRUE.equals(asset.getRepresentativeOverride()));
    }
}
