package com.playdata.calen.sharing.application.service;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.sharing.application.port.in.CreateRecordShareCommand;
import com.playdata.calen.sharing.application.port.in.CreateRecordShareUseCase;
import com.playdata.calen.sharing.application.port.out.ShareParticipantsPort;
import com.playdata.calen.sharing.application.port.out.ShareRequestsPort;
import com.playdata.calen.sharing.application.port.out.ShareSourcePort;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Receipt;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Submission;
import com.playdata.calen.sharing.domain.policy.ShareCreationPolicy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Application workflow. Transaction and technology choices live in adapters. */
public final class CreateRecordShareService implements CreateRecordShareUseCase {
    private final ShareParticipantsPort participants;
    private final ShareSourcePort sources;
    private final ShareRequestsPort requests;
    private final Clock clock;

    public CreateRecordShareService(ShareParticipantsPort participants, ShareSourcePort sources,
            ShareRequestsPort requests, Clock clock) {
        this.participants = participants; this.sources = sources; this.requests = requests; this.clock = clock;
    }

    @Override
    public List<Receipt> create(Long userId, CreateRecordShareCommand command) {
        boolean edited = command.ledgerOverride() != null;
        ShareCreationPolicy.requireAllowedContent(command.kind(), edited);
        String memo = ShareCreationPolicy.normalizeMemo(command.shareMemo());
        var sender = participants.requireActiveActor(userId);
        var group = participants.requireGroupAccess(command.groupId(), userId);
        // Source first, then each source/recipient row: preserve the existing lock order.
        var content = sources.lockOwnedSource(userId, command.kind(), command.sourceId(), command.ledgerOverride());
        List<Receipt> result = new ArrayList<>();
        for (Long recipientId : new LinkedHashSet<>(command.recipientIds())) {
            if (userId.equals(recipientId)) throw new BadRequestException("본인에게 기록을 공유할 수 없습니다.");
            participants.requireGroupAccess(group.id(), recipientId);
            var recipient = participants.requireActiveActor(recipientId);
            var existing = requests.lockExisting(command.kind(), command.sourceId(), recipientId);
            if (existing.isPresent() && ShareCreationPolicy.reuse(command.kind(), group.id(), existing.get(), edited, content.fingerprint())) {
                result.add(requests.readLocked(existing.get().id()));
            } else {
                result.add(requests.savePending(existing.map(item -> item.id()).orElse(null),
                        new Submission(group, command.kind(), command.sourceId(), sender, recipient, content, memo, LocalDateTime.now(clock))));
            }
        }
        return List.copyOf(result);
    }
}
