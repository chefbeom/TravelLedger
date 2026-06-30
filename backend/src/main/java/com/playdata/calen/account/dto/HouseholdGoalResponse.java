package com.playdata.calen.account.dto;

import com.playdata.calen.account.domain.HouseholdGoalStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record HouseholdGoalResponse(
        Long id,
        String title,
        BigDecimal targetAmountKrw,
        BigDecimal currentAmountKrw,
        String progressBucket,
        LocalDate dueDate,
        HouseholdGoalStatus status,
        Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}