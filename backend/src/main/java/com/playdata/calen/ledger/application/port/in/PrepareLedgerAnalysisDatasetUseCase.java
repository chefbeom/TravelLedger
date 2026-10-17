package com.playdata.calen.ledger.application.port.in;

import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.*;

public interface PrepareLedgerAnalysisDatasetUseCase {
    Dataset prepare(Long owner, AnalysisPlan plan);
}
