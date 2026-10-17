package com.playdata.calen.ledger.application.service;

import com.playdata.calen.common.exception.TooManyRequestsException;
import com.playdata.calen.ledger.application.port.in.StartLedgerAnalysisUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerAnalysisAdmissionPort;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.Detail;
import java.time.Duration;

/** Owner/configuration -> optional admission lease -> reuse -> create/submit, without a DB transaction. */
public final class StartLedgerAnalysisService implements StartLedgerAnalysisUseCase {
    @Override public Detail start(LedgerAnalysisAdmissionPort operation, Duration duplicateWindow) {
        operation.requireActiveOwner();
        operation.requireConfigured();
        if (!operation.usesDistributedAdmission()) return admitted(operation, duplicateWindow);
        try (var lease = operation.tryAdmission()) {
            if (lease == null) {
                if (!hasText(operation.focusPrompt())) {
                    var processing = operation.processing(operation.now().minus(duplicateWindow));
                    if (processing.isPresent()) return processing.get();
                }
                throw new TooManyRequestsException("AI 분석 요청을 접수 중입니다. 잠시 후 다시 시도해 주세요.", 1);
            }
            return admitted(operation, duplicateWindow);
        }
    }
    private Detail admitted(LedgerAnalysisAdmissionPort operation, Duration duplicateWindow) {
        if (!hasText(operation.focusPrompt())) {
            var since = operation.now().minus(duplicateWindow);
            var processing = operation.processing(since);
            if (processing.isPresent()) return processing.get();
            var reusable = operation.reusable(since);
            if (reusable.isPresent()) return reusable.get();
        }
        operation.createProcessing();
        try { operation.submit(); }
        catch (RuntimeException failure) { operation.submissionFailed(failure); throw failure; }
        return operation.pendingDetail();
    }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
