package com.playdata.calen.travel.domain.policy;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.domain.TravelMediaType;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.domain.model.TravelMediaData.Gps;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TravelMediaPolicy {
    private TravelMediaPolicy() {}
    public static TravelMediaType type(TravelRecordType record, TravelMediaType requested) {
        if (record == TravelRecordType.MEMORY) {
            return TravelMediaType.PHOTO;
        }
        return requested != null ? requested : TravelMediaType.PHOTO;
    }

    public static void requireStoredType(TravelMediaType type, String contentType) {
        boolean image = contentType != null && contentType.startsWith("image/");
        boolean pdf = "application/pdf".equals(contentType);
        if ((type == null || type == TravelMediaType.PHOTO) && !image) {
            throw new BadRequestException("Photos must use JPG, PNG, WEBP, GIF, or BMP files.");
        }
        if (type == TravelMediaType.RECEIPT && !(image || pdf)) {
            throw new BadRequestException("Receipts must use JPG, PNG, WEBP, GIF, BMP, or PDF files.");
        }
    }

    public static Gps gps(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null) {
            return null;
        }
        return new Gps(latitude.setScale(7, RoundingMode.HALF_UP), longitude.setScale(7, RoundingMode.HALF_UP));
    }

    public static String caption(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
