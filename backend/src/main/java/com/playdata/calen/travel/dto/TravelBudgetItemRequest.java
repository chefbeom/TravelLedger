package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record TravelBudgetItemRequest(
        @NotBlank(message = "예산 카테고리를 입력해 주세요.")
        @Size(max = 80, message = "카테고리는 80자 이하여야 합니다.")
        String category,

        @NotBlank(message = "예산 항목 이름을 입력해 주세요.")
        @Size(max = 120, message = "항목 이름은 120자 이하여야 합니다.")
        String title,

        @NotNull(message = "예상 금액을 입력해 주세요.")
        @DecimalMin(value = "0.01", message = "금액은 0보다 커야 합니다.")
        BigDecimal amount,

        @NotBlank(message = "통화 코드를 입력해 주세요.")
        @Size(min = 3, max = 3, message = "통화 코드는 3자리여야 합니다.")
        String currencyCode,

        @DecimalMin(value = "0.000001", message = "환율은 0보다 커야 합니다.")
        BigDecimal exchangeRateToKrw,

        @Size(max = 500, message = "메모는 500자 이하여야 합니다.")
        String memo,

        Integer displayOrder
) {
}
