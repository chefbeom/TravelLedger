package com.playdata.calen.travel.domain.model;

import com.playdata.calen.travel.domain.TravelMediaType;
import com.playdata.calen.travel.domain.TravelRecordType;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** Detached cluster detail values. Content URLs belong to the inbound adapter. */
public final class TravelClusterDetailData {
    private TravelClusterDetailData() {}
    public record Media(
        Long id, Long planId, String planName, String planColorHex, Long recordId,
        TravelRecordType recordType, TravelMediaType mediaType, String originalFileName,
        String contentType, Long fileSize, String caption, String uploadedBy, LocalDateTime uploadedAt,
        LocalDate expenseDate, LocalTime expenseTime, String title, BigDecimal amount,
        String currencyCode, BigDecimal amountKrw, String country, String region, String placeName,
        BigDecimal latitude, BigDecimal longitude, BigDecimal gpsLatitude, BigDecimal gpsLongitude,
        Boolean representativeOverride
    ) {}
    public record Detail(
        Long id, Long representativeMediaId, Long representativeRecordId, BigDecimal latitude,
        BigDecimal longitude, int photoCount, int memoryCount, BigDecimal maxDistanceMeters,
        boolean representativeOverride, Media representativePhoto, List<Media> photos
    ) {}
    public record Page(Detail cluster, List<Media> photos, int page, int size, int totalPhotoCount, boolean hasNext) {}
}
