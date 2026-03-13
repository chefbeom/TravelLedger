package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.EntryType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CategoryGroupRequest(
        @NotBlank(message = "대분류명은 필수입니다.")
        String name,
        @NotNull(message = "수입/지출 구분은 필수입니다.")
        EntryType entryType,
        @Min(value = 0, message = "표시 순서는 0 이상이어야 합니다.")
        Integer displayOrder
) {
}
