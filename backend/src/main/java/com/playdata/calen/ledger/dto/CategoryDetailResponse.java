package com.playdata.calen.ledger.dto;

public record CategoryDetailResponse(
        Long id,
        Long groupId,
        String name,
        Integer displayOrder,
        boolean active
) {
}
