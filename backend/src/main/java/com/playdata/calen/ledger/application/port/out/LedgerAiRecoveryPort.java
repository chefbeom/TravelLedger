package com.playdata.calen.ledger.application.port.out;

import java.time.LocalDateTime;
import java.util.List;

/** Per-scan runtime ports. Jobs expose lazy facts/actions, never persistence entities or provider DTOs. */
public final class LedgerAiRecoveryPort {
    private LedgerAiRecoveryPort() {}

    public interface IdSource<J> {
        boolean available();
        long cursor();
        void remember(long cursor);
        List<J> page(long cursor, int limit);
    }

    public interface IdJob {
        long id();
        boolean running();
        boolean reconstructable();
        void rejectUnrecoverable();
        /** False is queue capacity only; unexpected runtime failures still propagate. */
        boolean trySubmit();
    }

    public interface AnalysisJob extends IdJob {
        boolean atCapacity();
    }

    public interface ImageJob extends IdJob {
        /** A failed owner reservation skips this item, not the rest of the scan. */
        boolean tryReserve();
    }

    public record WorkbookCursor(LocalDateTime createdAt, String id) {}

    public interface WorkbookSource {
        WorkbookCursor cursor();
        void remember(WorkbookCursor cursor);
        List<WorkbookJob> page(WorkbookCursor cursor, int limit);
    }

    public interface WorkbookJob {
        WorkbookCursor cursor();
        boolean running();
        boolean trySubmit();
    }
}
