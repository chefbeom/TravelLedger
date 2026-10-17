package com.playdata.calen.sharing.application.port.out;

import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Existing;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Receipt;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Submission;
import java.util.Optional;

public interface ShareRequestsPort {
    Optional<Existing> lockExisting(RecordShareKind kind, Long sourceId, Long recipientId);
    Receipt readLocked(Long id);
    Receipt savePending(Long existingId, Submission submission);
}
