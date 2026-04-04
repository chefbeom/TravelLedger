package com.playdata.calen.travel.dto;

import com.playdata.calen.travel.domain.TravelMediaType;
import com.playdata.calen.travel.domain.TravelRecordType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record TravelMediaResponse(
        Long id,
        Long planId,
        String planName,
        String planColorHex,
        Long recordId,
        TravelRecordType recordType,
        TravelMediaType mediaType,
        String originalFileName,
        String contentType,
        Long fileSize,
        String caption,
        String uploadedBy,
        LocalDateTime uploadedAt,
        String contentUrl,
        LocalDate expenseDate,
        LocalTime expenseTime,
        String title,
        BigDecimal amount,
        String currencyCode,
        BigDecimal amountKrw,
        String country,
        String region,
        String placeName,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal gpsLatitude,
        BigDecimal gpsLongitude,
        Boolean representativeOverride
) {
}
