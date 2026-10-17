package com.playdata.calen.ledger.domain.policy;

import com.playdata.calen.ledger.domain.model.LedgerAiJobData.*;
import java.util.Objects;

public final class LedgerAiJobLifecyclePolicy {
    private LedgerAiJobLifecyclePolicy() {}
    public static boolean isProcessing(String status) { return "PROCESSING".equals(status); }
    public static void requireVersionWhenNeeded(Job job) {
        Objects.requireNonNull(job, "job");
        if (job instanceof ExcelJob excel) Objects.requireNonNull(excel.version(), "Excel inference version");
    }
    public static void requireCompleteResult(Completion completion) {
        requireVersionWhenNeeded(completion.job());
        if (completion.resultJson() == null || completion.resultJson().isBlank())
            throw new IllegalStateException("Serialize the complete job result before finalization.");
        if (completion.job() instanceof ImageJob) Objects.requireNonNull(completion.image(), "image result metadata");
    }
    /** A zero-row CAS is an expected lost race/deletion, never an instruction to merge a detached entity. */
    public static boolean applied(int rows) {
        if (rows < 0 || rows > 1) throw new IllegalStateException("A job finalization must affect at most one row.");
        return rows == 1;
    }
}
