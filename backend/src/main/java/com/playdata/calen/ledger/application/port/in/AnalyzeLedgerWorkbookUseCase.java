package com.playdata.calen.ledger.application.port.in;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.Preview;

public interface AnalyzeLedgerWorkbookUseCase { Preview analyze(String preparedInput); }
