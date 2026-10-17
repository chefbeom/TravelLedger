package com.playdata.calen.ledger.application.service;
import com.playdata.calen.ledger.application.port.in.AnalyzeLedgerWorkbookUseCase;
import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.Preview;
import com.playdata.calen.ledger.domain.policy.LedgerWorkbookPreviewPolicy;

public final class AnalyzeLedgerWorkbookService implements AnalyzeLedgerWorkbookUseCase {
    private final PreparedLedgerWorkbookPort inputs;
    private final LedgerWorkbookExtractionPort extraction;
    public AnalyzeLedgerWorkbookService(PreparedLedgerWorkbookPort inputs, LedgerWorkbookExtractionPort extraction) {
        this.inputs = inputs; this.extraction = extraction;
    }
    @Override public Preview analyze(String preparedInput) {
        var workbook = inputs.read(preparedInput);
        return LedgerWorkbookPreviewPolicy.preview(workbook, extraction.extract(workbook.payloadJson()));
    }
}
