package com.playdata.calen.ledger.adapter.in.image;

import com.playdata.calen.ledger.application.port.in.StoreLedgerImageInputUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerImageInputReferencePort;
import com.playdata.calen.ledger.application.port.out.LedgerImageStoragePort;
import com.playdata.calen.ledger.application.service.StoreLedgerImageInputService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NonTransactionalLedgerImageInputStore implements StoreLedgerImageInputUseCase {
    private final StoreLedgerImageInputUseCase delegate;
    public NonTransactionalLedgerImageInputStore(LedgerImageStoragePort storage, LedgerImageInputReferencePort references) {
        delegate=new StoreLedgerImageInputService(storage,references);
    }
    @Override @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public boolean store(Long ownerId, Long analysisId, LedgerImageStoragePort.Upload image) {
        return delegate.store(ownerId,analysisId,image);
    }
}
