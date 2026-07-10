package com.playdata.calen.travel.dto;

import com.playdata.calen.travel.domain.TravelRecordType;
import java.time.LocalDate;
import java.time.LocalDateTime;

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
        String title,
        String country,
        String region,
        String placeName
) {
}
