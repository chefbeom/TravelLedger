package com.playdata.calen.ledger.adapter.in.jobs;
import com.playdata.calen.ledger.application.port.in.AnalyzeLedgerWorkbookUseCase;
import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.application.service.AnalyzeLedgerWorkbookService;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.Preview;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class NonTransactionalLedgerWorkbookAnalysis implements AnalyzeLedgerWorkbookUseCase {
    private final AnalyzeLedgerWorkbookUseCase delegate;
    public NonTransactionalLedgerWorkbookAnalysis(PreparedLedgerWorkbookPort inputs, LedgerWorkbookExtractionPort extraction) {
        delegate = new AnalyzeLedgerWorkbookService(inputs,extraction);
    }
    @Override public Preview analyze(String preparedInput) { return delegate.analyze(preparedInput); }
}
