package com.playdata.calen.ledger.adapter.out.persistence;

import com.playdata.calen.ledger.application.port.out.LedgerAiJobStatePort;
import com.playdata.calen.ledger.domain.model.LedgerAiJobData.*;
import com.playdata.calen.ledger.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Repository CAS methods each open the same independent short transaction as the legacy workers. */
@Component
@RequiredArgsConstructor
public class JpaLedgerAiJobStateAdapter implements LedgerAiJobStatePort {
    private final LedgerAiAnalysisHistoryRepository analyses;
    private final LedgerImageAnalysisRequestRepository images;
    private final LedgerExcelAnalysisJobRepository excel;
    @Override public int completeProcessing(Completion command) {
        if (command.job() instanceof AnalysisJob job)
            return analyses.completeProcessing(job.id(), job.ownerId(), command.summary(), command.resultJson());
        if (command.job() instanceof ImageJob job)
            return images.completeProcessing(job.id(), job.ownerId(), command.image().documentType(), command.image().rawText(),
                    command.summary(), command.resultJson(), command.at());
        var job = (ExcelJob) command.job();
        return excel.completeProcessing(job.id(), job.ownerId(), job.version(), command.resultJson());
    }
    @Override public int failProcessing(Failure command) {
        if (command.job() instanceof AnalysisJob job) return analyses.failProcessing(job.id(), job.ownerId(), command.errorMessage());
        if (command.job() instanceof ImageJob job)
            return images.failProcessing(job.id(), job.ownerId(), command.errorMessage(), command.summary(), command.at());
        var job = (ExcelJob) command.job();
        return excel.failProcessing(job.id(), job.ownerId(), job.version(), command.errorMessage());
    }
    @Override public int cancelProcessing(Cancellation command) {
        return images.cancelProcessing(command.job().id(), command.job().ownerId(), command.summary(), command.at());
    }
}
