package com.playdata.calen.sharing.application.port.out;

import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Content;
import com.playdata.calen.sharing.domain.model.ShareCreationData.LedgerDraft;

public interface ShareSourcePort {
    /** Holds the owned source lock until the enclosing creation transaction ends. */
    Content lockOwnedSource(Long userId, RecordShareKind kind, Long sourceId, LedgerDraft override);
}
