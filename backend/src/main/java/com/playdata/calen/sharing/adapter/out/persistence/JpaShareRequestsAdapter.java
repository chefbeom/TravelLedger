package com.playdata.calen.sharing.adapter.out.persistence;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.sharing.application.port.out.ShareRequestsPort;
import com.playdata.calen.sharing.domain.RecordShare;
import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.RecordShareStatus;
import com.playdata.calen.sharing.domain.model.ShareCreationData.*;
import com.playdata.calen.sharing.repository.RecordShareRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor @Transactional(propagation = Propagation.MANDATORY)
public class JpaShareRequestsAdapter implements ShareRequestsPort {
    private final RecordShareRepository shares;
    private final AppUserService users;
    private final ShareSnapshotCodec codec;

    @Override public Optional<Existing> lockExisting(RecordShareKind kind, Long sourceId, Long recipientId) {
        return shares.findLockedByKindAndSourceIdAndRecipientId(kind, sourceId, recipientId)
                .map(row -> new Existing(row.getId(), row.getGroupId(), row.getStatus(), codec.fingerprint(row.getSnapshotJson())));
    }

    @Override public Receipt readLocked(Long id) { return receipt(lockedRow(id)); }

    @Override public Receipt savePending(Long existingId, Submission submission) {
        RecordShare row = existingId == null ? new RecordShare() : lockedRow(existingId);
        row.setGroupId(submission.group().id()); row.setGroupName(submission.group().name());
        row.setKind(submission.kind()); row.setSourceId(submission.sourceId());
        row.setSender(users.getRequiredUser(submission.sender().id())); row.setRecipient(users.getRequiredUser(submission.recipient().id()));
        row.setTitle(submission.content().title()); row.setSnapshotJson(codec.encode(submission.content().ledger()));
        row.setShareMemo(submission.shareMemo()); row.setStatus(RecordShareStatus.PENDING);
        row.setCreatedAt(submission.createdAt()); row.setRespondedAt(null);
        return receipt(shares.save(row));
    }

    private RecordShare lockedRow(Long id) {
        // The earlier locking query already manages this row in the same persistence context.
        return shares.findById(id).orElseThrow(() -> new NotFoundException("접근 가능한 기록 공유 요청을 찾을 수 없습니다."));
    }

    private Receipt receipt(RecordShare row) {
        return new Receipt(row.getId(), row.getGroupId(), row.getGroupName(), row.getKind(), row.getSourceId(),
                new Actor(row.getSender().getId(), row.getSender().getDisplayName()), new Actor(row.getRecipient().getId(), row.getRecipient().getDisplayName()),
                row.getStatus(), row.getTitle(), codec.decode(row.getSnapshotJson()), row.getImportedLedgerEntryId(),
                row.getCreatedAt(), row.getRespondedAt(), row.getShareMemo());
    }
}
