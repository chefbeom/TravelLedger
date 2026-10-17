package com.playdata.calen.sharing.application.port.in;

import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.model.ShareCreationData.LedgerDraft;
import java.util.List;

public record CreateRecordShareCommand(Long groupId, RecordShareKind kind, Long sourceId,
        List<Long> recipientIds, String shareMemo, LedgerDraft ledgerOverride) {
    public CreateRecordShareCommand { recipientIds = List.copyOf(recipientIds); }
}
