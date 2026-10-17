package com.playdata.calen.ledger.adapter.in.jobs;

import com.playdata.calen.ledger.application.port.in.FinalizeLedgerAiJobUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerAiJobStatePort;
import com.playdata.calen.ledger.application.service.FinalizeLedgerAiJobService;
import com.playdata.calen.ledger.domain.model.LedgerAiJobData.*;
import org.springframework.stereotype.Component;

/** Worker/legacy compatibility entry point; short REQUIRES_NEW writes live in the persistence adapter. */
@Component
public class LedgerAiJobFinalizer implements FinalizeLedgerAiJobUseCase {
    private final FinalizeLedgerAiJobUseCase delegate;
    public LedgerAiJobFinalizer(LedgerAiJobStatePort states) { delegate = new FinalizeLedgerAiJobService(states); }
    @Override public boolean complete(Completion completion) { return delegate.complete(completion); }
    @Override public boolean fail(Failure failure) { return delegate.fail(failure); }
    @Override public boolean cancel(Cancellation cancellation) { return delegate.cancel(cancellation); }
}
