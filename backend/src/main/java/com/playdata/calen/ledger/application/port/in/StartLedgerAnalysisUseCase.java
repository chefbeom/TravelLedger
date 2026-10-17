package com.playdata.calen.ledger.application.port.in;

import com.playdata.calen.ledger.application.port.out.LedgerAnalysisAdmissionPort;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.Detail;
import java.time.Duration;

public interface StartLedgerAnalysisUseCase {
    Detail start(LedgerAnalysisAdmissionPort operation, Duration duplicateWindow);
}
