package com.playdata.calen.ledger.adapter.in.transaction;

import com.playdata.calen.ledger.application.port.in.PrepareLedgerEntryUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerExchangeRatePort;
import com.playdata.calen.ledger.application.port.out.LedgerReferencesPort;
import com.playdata.calen.ledger.application.service.PrepareLedgerEntryService;
import com.playdata.calen.ledger.domain.model.LedgerPreparationData.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionalLedgerPreparation implements PrepareLedgerEntryUseCase {
    private final PrepareLedgerEntryUseCase delegate;

    public TransactionalLedgerPreparation(LedgerReferencesPort references, LedgerExchangeRatePort rates) {
        delegate = new PrepareLedgerEntryService(references, rates);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Prepared prepare(Long ownerId, Draft draft, boolean sharingPreview) {
        return delegate.prepare(ownerId, draft, sharingPreview);
    }
}
