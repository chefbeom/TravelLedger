package com.playdata.calen.sharing.adapter.in.transaction;

import com.playdata.calen.sharing.application.port.in.CreateRecordShareCommand;
import com.playdata.calen.sharing.application.port.in.CreateRecordShareUseCase;
import com.playdata.calen.sharing.application.port.out.ShareParticipantsPort;
import com.playdata.calen.sharing.application.port.out.ShareRequestsPort;
import com.playdata.calen.sharing.application.port.out.ShareSourcePort;
import com.playdata.calen.sharing.application.service.CreateRecordShareService;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Receipt;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One transaction encloses group checks, source locking and every recipient. */
@Component
public class TransactionalCreateRecordShare implements CreateRecordShareUseCase {
    private final CreateRecordShareUseCase delegate;

    public TransactionalCreateRecordShare(ShareParticipantsPort participants, ShareSourcePort sources, ShareRequestsPort requests) {
        delegate = new CreateRecordShareService(participants, sources, requests, Clock.systemDefaultZone());
    }

    @Override
    @Transactional
    public List<Receipt> create(Long userId, CreateRecordShareCommand command) { return delegate.create(userId, command); }
}
