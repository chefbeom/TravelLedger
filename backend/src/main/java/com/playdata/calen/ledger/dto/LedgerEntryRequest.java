package com.playdata.calen.ledger.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.playdata.calen.ledger.domain.EntryType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record LedgerEntryRequest(
        @NotNull(message = "거래일은 필수입니다.")
        LocalDate entryDate,
        @JsonFormat(pattern = "HH:mm")
        LocalTime entryTime,
        @NotBlank(message = "제목은 필수입니다.")
        String title,
        String memo,
        @NotNull(message = "금액은 필수입니다.")
        @DecimalMin(value = "0.01", message = "금액은 0보다 커야 합니다.")
        BigDecimal amount,
        @NotNull(message = "수입/지출 구분은 필수입니다.")
        EntryType entryType,
        @NotNull(message = "대분류는 필수입니다.")
        Long categoryGroupId,
        Long categoryDetailId,
        @NotNull(message = "결제수단은 필수입니다.")
        Long paymentMethodId
) {
}
