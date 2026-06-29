package com.playdata.calen.ledger.dto;

import com.playdata.calen.ledger.domain.EntryType;
import jakarta.validation.constraints.Size;

public record LedgerClassificationPreviewRequest(
        @Size(max = 120) String title,
        @Size(max = 500) String memo,
        EntryType entryType
) {
}