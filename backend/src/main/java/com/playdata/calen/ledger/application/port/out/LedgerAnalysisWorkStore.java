package com.playdata.calen.ledger.application.port.out;

public interface LedgerAnalysisWorkStore {
    String status(Long owner, Long history);
    int savePayload(Long owner, Long history, String payload);
}
