package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData.AnalysisPlan;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.Dataset;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Request;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Result;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.AnalysisResult;

/** Preserve serialization and provider-mapping timing without exposing the stored-v2 DTO. */
public interface LedgerAnalysisWorkFormats {
    Prepared prepare(AnalysisPlan plan, Dataset dataset);
    AnalysisResult response(Long id, AnalysisPlan plan, Dataset dataset, Result remote);
    String serializeResult(AnalysisResult result);
    String failureText(RuntimeException failure);
    interface Prepared {
        String serialize();
        Request request();
    }
}
