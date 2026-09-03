package com.playdata.calen.ledger.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record DisplayOrderRequest(
        @NotEmpty(message = "순서 목록은 하나 이상이어야 합니다.")
        List<@NotNull(message = "순서 항목 ID는 필수입니다.") Long> orderedIds
) {
}
