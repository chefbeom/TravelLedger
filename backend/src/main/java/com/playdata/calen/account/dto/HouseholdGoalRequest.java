package com.playdata.calen.account.dto;

import com.playdata.calen.account.domain.HouseholdGoalStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record HouseholdGoalRequest(
        @NotBlank
        @Size(max = 120)
        String title,

        @DecimalMin(value = "0.01")
        @Digits(integer = 17, fraction = 2)
        BigDecimal targetAmountKrw,

        @DecimalMin(value = "0.00")
        @Digits(integer = 17, fraction = 2)
        BigDecimal currentAmountKrw,

        LocalDate dueDate,

        HouseholdGoalStatus status
) {
}