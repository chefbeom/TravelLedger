package com.playdata.calen.ledger.ai;

public interface LedgerAiRemoteClient {

    LedgerAiRemoteResponse analyze(Object payload);
}