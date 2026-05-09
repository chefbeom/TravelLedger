package com.playdata.calen.ledger.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record LedgerEntryBulkUpdateRequest(
        @NotEmpty(message = "변경할 거래를 선택해 주세요.")
        @Size(max = 100, message = "한 번에 변경할 수 있는 거래는 100건까지입니다.")
        List<Long> entryIds,
        Long categoryGroupId,
        Long categoryDetailId,
        Long paymentMethodId
) {
}
