package com.playdata.calen.travel.dto;

import com.playdata.calen.travel.domain.TravelRecordType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record TravelMemoryRecordResponse(
        Long id,
        Long planId,
        String planName,
        String planColorHex,
        TravelRecordType recordType,
        LocalDate memoryDate,
        LocalTime memoryTime,
        String category,
        String title,
        String country,
        String region,
        String placeName,
        BigDecimal latitude,
        BigDecimal longitude,
        Boolean sharedWithCommunity,
        String memo
) {
}
