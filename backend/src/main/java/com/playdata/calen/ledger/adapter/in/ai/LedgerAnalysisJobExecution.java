package com.playdata.calen.ledger.adapter.in.ai;

import com.playdata.calen.ledger.application.port.in.*;
import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.application.service.ExecuteLedgerAnalysisService;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData.AnalysisPlan;
import java.time.LocalDateTime;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class LedgerAnalysisJobExecution implements ExecuteLedgerAnalysisUseCase {
    private final ExecuteLedgerAnalysisService delegate;
    public LedgerAnalysisJobExecution(PrepareLedgerAnalysisDatasetUseCase datasets, LedgerAnalysisWorkStore jobs,
            LedgerAnalysisWorkFormats formats, LedgerSpendingAnalysisPort provider, FinalizeLedgerAiJobUseCase finalizer,
            LedgerAnalysisWorkMonitoring monitoring) {
        delegate = new ExecuteLedgerAnalysisService(datasets, jobs, formats, provider, finalizer, monitoring, LocalDateTime::now);
    }
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void process(Long owner, Long history, AnalysisPlan plan, BooleanSupplier ownsLease) { delegate.process(owner, history, plan, ownsLease); }
}
