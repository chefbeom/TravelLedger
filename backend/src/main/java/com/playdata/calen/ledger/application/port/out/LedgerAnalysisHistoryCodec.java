package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.AnalysisResult;

public interface LedgerAnalysisHistoryCodec {
    AnalysisResult read(String resultJson);
}
