package com.playdata.calen.ledger.application.port.in;

import com.playdata.calen.ledger.application.port.out.LedgerImageStoragePort.Upload;

public interface StoreLedgerImageInputUseCase {
    /** False selects the existing temporary-spool fallback when durable storage is unavailable. */
    boolean store(Long ownerId, Long analysisId, Upload image);
}
