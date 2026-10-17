package com.playdata.calen.ledger.application.service;

import com.playdata.calen.ledger.application.port.in.FinalizeLedgerAiJobUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerAiJobStatePort;
import com.playdata.calen.ledger.domain.model.LedgerAiJobData.*;
import com.playdata.calen.ledger.domain.policy.LedgerAiJobLifecyclePolicy;

public final class FinalizeLedgerAiJobService implements FinalizeLedgerAiJobUseCase {
    private final LedgerAiJobStatePort states;
    public FinalizeLedgerAiJobService(LedgerAiJobStatePort states) { this.states = states; }
    @Override public boolean complete(Completion command) {
        LedgerAiJobLifecyclePolicy.requireCompleteResult(command);
        return LedgerAiJobLifecyclePolicy.applied(states.completeProcessing(command));
    }
    @Override public boolean fail(Failure command) {
        LedgerAiJobLifecyclePolicy.requireVersionWhenNeeded(command.job());
        return LedgerAiJobLifecyclePolicy.applied(states.failProcessing(command));
    }
    @Override public boolean cancel(Cancellation command) {
        return LedgerAiJobLifecyclePolicy.applied(states.cancelProcessing(command));
    }
}
