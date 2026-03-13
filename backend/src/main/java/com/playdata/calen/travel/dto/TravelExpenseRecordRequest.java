package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record TravelExpenseRecordRequest(
        @NotNull(message = "기록 날짜를 선택해 주세요.")
        LocalDate expenseDate,

        LocalTime expenseTime,

        @NotBlank(message = "기록 카테고리를 입력해 주세요.")
        @Size(max = 80, message = "카테고리는 80자 이하여야 합니다.")
        String category,

        @NotBlank(message = "기록 제목을 입력해 주세요.")
        @Size(max = 120, message = "기록 제목은 120자 이하여야 합니다.")
        String title,

        @NotNull(message = "사용 금액을 입력해 주세요.")
        @DecimalMin(value = "0.01", message = "금액은 0보다 커야 합니다.")
        BigDecimal amount,

        @NotBlank(message = "통화 코드를 입력해 주세요.")
        @Size(min = 3, max = 3, message = "통화 코드는 3자리여야 합니다.")
        String currencyCode,

        @DecimalMin(value = "0.000001", message = "환율은 0보다 커야 합니다.")
        BigDecimal exchangeRateToKrw,

        @Size(max = 80, message = "방문 국가는 80자 이하여야 합니다.")
        String country,

        @Size(max = 120, message = "여행 지역은 120자 이하여야 합니다.")
        String region,

        @Size(max = 160, message = "방문 장소는 160자 이하여야 합니다.")
        String placeName,

        BigDecimal latitude,

        BigDecimal longitude,

        @Size(max = 500, message = "메모는 500자 이하여야 합니다.")
        String memo
) {
}
