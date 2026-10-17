package com.playdata.calen.ledger.adapter.in.ai;

import com.playdata.calen.ledger.application.port.in.PrepareLedgerAnalysisDatasetUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerAnalysisDatasetStore;
import com.playdata.calen.ledger.application.service.PrepareLedgerAnalysisDatasetService;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.*;
import org.springframework.stereotype.Component;

/** Intentionally no transaction annotation: do not broaden the previous per-query transactions. */
@Component
public class LedgerAnalysisDatasetPreparation implements PrepareLedgerAnalysisDatasetUseCase {
    private final PrepareLedgerAnalysisDatasetService delegate;
    public LedgerAnalysisDatasetPreparation(LedgerAnalysisDatasetStore store) { delegate = new PrepareLedgerAnalysisDatasetService(store); }
    @Override public Dataset prepare(Long owner, AnalysisPlan plan) { return delegate.prepare(owner, plan); }
}
