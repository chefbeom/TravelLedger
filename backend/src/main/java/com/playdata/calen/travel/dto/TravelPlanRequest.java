package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record TravelPlanRequest(
        @NotBlank(message = "Enter a travel name.")
        @Size(max = 120, message = "Travel name must be 120 characters or fewer.")
        String name,

        @Size(max = 120, message = "Destination must be 120 characters or fewer.")
        String destination,

        @NotNull(message = "Select a travel start date.")
        LocalDate startDate,

        @NotNull(message = "Select a travel end date.")
        LocalDate endDate,

        @NotBlank(message = "Enter the home currency.")
        @Size(min = 3, max = 3, message = "Currency code must be 3 characters.")
        String homeCurrency,

        @NotNull(message = "Enter the travel head count.")
        @Positive(message = "Head count must be at least 1.")
        Integer headCount,

        @Size(max = 20, message = "Travel status must be 20 characters or fewer.")
        String status,

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Travel color must use #RRGGBB format.")
        String colorHex,

        @Size(max = 500, message = "Memo must be 500 characters or fewer.")
        String memo
) {
}
