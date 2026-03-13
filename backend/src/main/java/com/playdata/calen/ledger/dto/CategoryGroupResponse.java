package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.EntryType;
import java.util.List;

public record CategoryGroupResponse(
        Long id,
        String name,
        EntryType entryType,
        Integer displayOrder,
        boolean active,
        List<CategoryDetailResponse> details
) {
}
