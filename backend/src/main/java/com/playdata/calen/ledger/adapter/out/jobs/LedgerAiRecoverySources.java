package com.playdata.calen.ledger.adapter.out.jobs;

import com.playdata.calen.common.exception.TooManyRequestsException;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.application.port.out.LedgerAiRecoveryPort.*;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.repository.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.*;
import org.springframework.data.domain.PageRequest;

/** Runtime bindings, not a second registry: reuse the original tasks, cursors, leases and finalizers. */
public final class LedgerAiRecoverySources {
    private LedgerAiRecoverySources() {}

    public static IdSource<AnalysisJob> analysis(LedgerAiAnalysisHistoryRepository histories, WorkLeaseService leases,
            BooleanSupplier available, LongSupplier cursor, LongConsumer remember, LongPredicate running,
            LongPredicate atCapacity, Consumer<LedgerAiAnalysisHistory> reject, Consumer<LedgerAiAnalysisHistory> submit) {
        return new IdPages<>(available, cursor, remember, (after, limit) ->
                histories.findAllByStatusAndCreatedAtBeforeAndIdGreaterThanOrderByIdAsc(LedgerAiAnalysisStatus.PROCESSING,
                        LocalDateTime.now().minusMinutes(1), after, PageRequest.of(0, limit)).stream().<AnalysisJob>map(history -> new AnalysisJob() {
                    @Override public long id() { return history.getId(); }
                    @Override public boolean running() { return running.test(history.getId()); }
                    @Override public boolean atCapacity() { return atCapacity.test(history.getOwner().getId()); }
                    @Override public boolean reconstructable() { return history.getFocusPrompt() != null; }
                    @Override public void rejectUnrecoverable() {
                        try (var lease = leases.tryAcquire("analysis-job:" + history.getId(), Duration.ofMinutes(2))) {
                            if (lease != null) reject.accept(history);
                        }
                    }
                    @Override public boolean trySubmit() { return submitUnlessFull(() -> submit.accept(history)); }
                }).toList());
    }

    public static IdSource<ImageJob> images(LedgerImageAnalysisRequestRepository histories, WorkLeaseService leases,
            BooleanSupplier available, LongSupplier cursor, LongConsumer remember, LongPredicate running,
            Predicate<LedgerImageAnalysisRequest> storedImage, Consumer<LedgerImageAnalysisRequest> reject,
            LongConsumer reserve, Consumer<LedgerImageAnalysisRequest> submit) {
        return new IdPages<>(available, cursor, remember, (after, limit) ->
                histories.findAllByStatusAndCreatedAtBeforeAndIdGreaterThanOrderByIdAsc(LedgerImageAnalysisStatus.PROCESSING,
                        LocalDateTime.now().minusMinutes(1), after, PageRequest.of(0, limit)).stream().<ImageJob>map(history -> new ImageJob() {
                    @Override public long id() { return history.getId(); }
                    @Override public boolean running() { return running.test(history.getId()); }
                    @Override public boolean reconstructable() { return storedImage.test(history) && history.getEffectivePrompt() != null; }
                    @Override public void rejectUnrecoverable() {
                        try (var lease = leases.tryAcquire("image-job:" + history.getId(), Duration.ofMinutes(30))) {
                            if (lease != null) reject.accept(history);
                        }
                    }
                    @Override public boolean tryReserve() { return submitUnlessFull(() -> reserve.accept(history.getOwner().getId())); }
                    @Override public boolean trySubmit() { return submitUnlessFull(() -> submit.accept(history)); }
                }).toList());
    }

    public static WorkbookSource workbooks(LedgerExcelAnalysisJobRepository jobs, Supplier<WorkbookCursor> cursor,
            Consumer<WorkbookCursor> remember, Predicate<String> running, Consumer<LedgerExcelAnalysisJob> submit) {
        // Excel snapshots one cutoff before reading its cursor; ID scans sample the clock for every page.
        LocalDateTime before = LocalDateTime.now().minusMinutes(1);
        return new WorkbookSource() {
            @Override public WorkbookCursor cursor() { return cursor.get(); }
            @Override public void remember(WorkbookCursor position) { remember.accept(position); }
            @Override public List<WorkbookJob> page(WorkbookCursor position, int limit) {
                return jobs.findRecoveryPage("PROCESSING", before, position.createdAt(), position.id(), PageRequest.of(0, limit))
                        .stream().<WorkbookJob>map(job -> new WorkbookJob() {
                            @Override public WorkbookCursor cursor() { return new WorkbookCursor(job.getCreatedAt(), job.getId()); }
                            @Override public boolean running() { return running.test(job.getId()); }
                            @Override public boolean trySubmit() { return submitUnlessFull(() -> submit.accept(job)); }
                        }).toList();
            }
        };
    }

    private static boolean submitUnlessFull(Runnable submission) {
        try { submission.run(); return true; }
        catch (TooManyRequestsException capacity) { return false; }
    }

    private record IdPages<J>(BooleanSupplier ready, LongSupplier position, LongConsumer save,
            BiFunction<Long, Integer, List<J>> query) implements IdSource<J> {
        @Override public boolean available() { return ready.getAsBoolean(); }
        @Override public long cursor() { return position.getAsLong(); }
        @Override public void remember(long cursor) { save.accept(cursor); }
        @Override public List<J> page(long cursor, int limit) { return query.apply(cursor, limit); }
    }
}
