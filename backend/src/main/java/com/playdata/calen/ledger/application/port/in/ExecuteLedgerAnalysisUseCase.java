package com.playdata.calen.ledger.application.port.in;

import com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData.AnalysisPlan;
import java.util.function.BooleanSupplier;

public interface ExecuteLedgerAnalysisUseCase {
    void process(Long owner, Long history, AnalysisPlan plan, BooleanSupplier ownsLease);
}
