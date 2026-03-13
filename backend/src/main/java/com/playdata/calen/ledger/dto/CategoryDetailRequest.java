package com.playdata.calen.ledger.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CategoryDetailRequest(
        @NotNull(message = "대분류 ID는 필수입니다.")
        Long groupId,
        @NotBlank(message = "소분류명은 필수입니다.")
        String name,
        @Min(value = 0, message = "표시 순서는 0 이상이어야 합니다.")
        Integer displayOrder
) {
}
