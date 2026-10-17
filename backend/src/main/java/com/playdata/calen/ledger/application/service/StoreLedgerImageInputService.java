package com.playdata.calen.ledger.application.service;

import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.application.port.in.StoreLedgerImageInputUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerImageInputReferencePort;
import com.playdata.calen.ledger.application.port.out.LedgerImageStoragePort;

/** Durable input upload followed by a short reference update, not a job status transition. */
public final class StoreLedgerImageInputService implements StoreLedgerImageInputUseCase {
    private final LedgerImageStoragePort storage;
    private final LedgerImageInputReferencePort references;

    public StoreLedgerImageInputService(LedgerImageStoragePort storage, LedgerImageInputReferencePort references) {
        this.storage=storage;
        this.references=references;
    }

    @Override public boolean store(Long ownerId, Long analysisId, LedgerImageStoragePort.Upload image) {
        if (!storage.supportsStorage()) return false;
        var stored=storage.store(ownerId,analysisId,image);
        if (references.attach(ownerId,analysisId,stored)==0) {
            storage.delete(stored.objectKey());
            throw new NotFoundException("이미지 분석 기록을 찾을 수 없습니다.");
        }
        return true;
    }
}
