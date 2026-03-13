package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record TravelMemoryRecordRequest(
        @NotNull(message = "Select a memory date.")
        LocalDate memoryDate,

        LocalTime memoryTime,

        @NotBlank(message = "Enter a memory category.")
        @Size(max = 80, message = "Memory category must be 80 characters or fewer.")
        String category,

        @NotBlank(message = "Enter a memory title.")
        @Size(max = 120, message = "Memory title must be 120 characters or fewer.")
        String title,

        @Size(max = 80, message = "Country must be 80 characters or fewer.")
        String country,

        @Size(max = 120, message = "Region must be 120 characters or fewer.")
        String region,

        @Size(max = 160, message = "Place name must be 160 characters or fewer.")
        String placeName,

        BigDecimal latitude,

        BigDecimal longitude,

        Boolean sharedWithCommunity,

        @Size(max = 500, message = "Memo must be 500 characters or fewer.")
        String memo
) {
}
