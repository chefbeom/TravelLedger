package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.RecurringLedgerMode;
import com.playdata.calen.ledger.domain.RecurringLedgerScheduleType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record RecurringLedgerRuleRequest(
        @NotBlank(message = "정기 입출금 제목을 입력해 주세요.")
        @Size(max = 120, message = "제목은 120자 이내로 입력해 주세요.")
        String title,
        @Size(max = 500, message = "메모는 500자 이내로 입력해 주세요.")
        String memo,
        @NotNull(message = "금액을 입력해 주세요.")
        @DecimalMin(value = "0.01", message = "금액은 0보다 커야 합니다.")
        @Digits(integer = 13, fraction = 2, message = "금액은 정수 13자리, 소수 2자리 이내로 입력해 주세요.")
        BigDecimal amount,
        @NotNull(message = "수입/지출 유형을 선택해 주세요.")
        EntryType entryType,
        @Min(value = 1, message = "반복 날짜는 1일 이상이어야 합니다.")
        @Max(value = 31, message = "반복 날짜는 31일 이하여야 합니다.")
        Integer dayOfMonth,
        RecurringLedgerScheduleType scheduleType,
        @Min(value = 1, message = "달 반복 간격은 1개월 이상이어야 합니다.")
        @Max(value = 120, message = "달 반복 간격은 120개월 이하여야 합니다.")
        Integer monthInterval,
        @Min(value = 1, message = "반복 간격은 1일 이상이어야 합니다.")
        @Max(value = 3650, message = "반복 간격은 3650일 이하여야 합니다.")
        Integer intervalDays,
        @NotNull(message = "시작일을 선택해 주세요.")
        LocalDate startDate,
        LocalDate endDate,
        @NotNull(message = "등록 방식을 선택해 주세요.")
        RecurringLedgerMode mode,
        @NotNull(message = "대분류를 선택해 주세요.")
        Long categoryGroupId,
        Long categoryDetailId,
        Long paymentMethodId,
        Boolean active
) {
}
