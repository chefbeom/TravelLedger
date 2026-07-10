package com.playdata.calen.travel.dto;

import com.playdata.calen.travel.domain.TravelRecordType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record TravelPhotoFrameMediaResponse(
        Long id,
        Long planId,
        String planName,
        TravelRecordType recordType,
        String originalFileName,
        String caption,
        LocalDateTime uploadedAt,
        String contentUrl,
        LocalDate expenseDate,
        LocalTime expenseTime,
        String title,
        String country,
        String region,
        String placeName,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal gpsLatitude,
        BigDecimal gpsLongitude
) {
}
