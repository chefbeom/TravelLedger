package com.playdata.calen.ledger.application.port.in;

import com.playdata.calen.ledger.domain.model.LedgerAiJobData.*;

public interface FinalizeLedgerAiJobUseCase {
    boolean complete(Completion completion);
    boolean fail(Failure failure);
    boolean cancel(Cancellation cancellation);
}
