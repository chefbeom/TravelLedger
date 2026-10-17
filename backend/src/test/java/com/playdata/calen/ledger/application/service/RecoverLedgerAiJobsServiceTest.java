package com.playdata.calen.ledger.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.ledger.application.port.out.LedgerAiRecoveryPort.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecoverLedgerAiJobsServiceTest {
    private final RecoverLedgerAiJobsService service = new RecoverLedgerAiJobsService();

    @Test void unavailableIdScansDoNotReadCursorOrQueryData() {
        IdSource<AnalysisJob> analysis = mock(IdSource.class);
        IdSource<ImageJob> images = mock(IdSource.class);
        service.recoverAnalysis(analysis); service.recoverImages(images);
        verify(analysis).available(); verify(images).available();
        verifyNoMoreInteractions(analysis, images);
    }

    @Test void analysisSkipsRunningAndOwnerCapacityBeforeReadingInputAndStopsAtQueueCapacity() {
        IdSource<AnalysisJob> source = mock(IdSource.class);
        AnalysisJob running = mock(AnalysisJob.class), busy = mock(AnalysisJob.class), legacy = mock(AnalysisJob.class), full = mock(AnalysisJob.class), later = mock(AnalysisJob.class);
        when(source.available()).thenReturn(true);
        when(source.page(0, 100)).thenReturn(List.of(running, busy, legacy, full, later));
        when(running.id()).thenReturn(1L); when(running.running()).thenReturn(true);
        when(busy.id()).thenReturn(2L); when(busy.atCapacity()).thenReturn(true);
        when(legacy.id()).thenReturn(3L);
        when(full.id()).thenReturn(4L); when(full.reconstructable()).thenReturn(true);
        when(later.id()).thenReturn(5L);
        service.recoverAnalysis(source);
        verify(running, never()).atCapacity(); verify(running, never()).reconstructable();
        verify(busy, never()).reconstructable(); verify(legacy).rejectUnrecoverable(); verify(legacy, never()).trySubmit();
        verify(full).trySubmit(); verify(later, never()).running(); verify(source).remember(4L); verify(source, never()).remember(0L);
    }

    @Test void imageReservationCapacitySkipsOneJobButSubmissionCapacityStopsTheScan() {
        IdSource<ImageJob> source = mock(IdSource.class);
        ImageJob running = mock(ImageJob.class), legacy = mock(ImageJob.class), busy = mock(ImageJob.class), full = mock(ImageJob.class), later = mock(ImageJob.class);
        when(source.available()).thenReturn(true);
        when(source.page(0, 100)).thenReturn(List.of(running, legacy, busy, full, later));
        when(running.id()).thenReturn(1L); when(running.running()).thenReturn(true);
        when(legacy.id()).thenReturn(2L); when(busy.id()).thenReturn(3L); when(busy.reconstructable()).thenReturn(true);
        when(full.id()).thenReturn(4L); when(full.reconstructable()).thenReturn(true); when(full.tryReserve()).thenReturn(true);
        when(later.id()).thenReturn(5L);
        service.recoverImages(source);
        verify(running, never()).reconstructable(); verify(legacy).rejectUnrecoverable(); verify(legacy, never()).tryReserve();
        verify(busy).tryReserve(); verify(busy, never()).trySubmit();
        var order = inOrder(full); order.verify(full).reconstructable(); order.verify(full).tryReserve(); order.verify(full).trySubmit();
        verify(later, never()).running(); verify(source).remember(4L); verify(source, never()).remember(0L);
    }

    @Test void boundedAnalysisScanResumesBeyond500RatherThanStarvingTheTail() {
        var source = ids(501, true);
        service.recoverAnalysis(source);
        assertThat(source.cursor).isEqualTo(500); assertThat(source.pages).containsExactly(0L, 100L, 200L, 300L, 400L);
        service.recoverAnalysis(source);
        assertThat(source.cursor).isZero(); assertThat(source.visited).containsExactlyElementsOf(sequence(1, 501));
    }

    @Test void boundedImageScanResumesBeyond500EvenWhenEveryEarlierJobIsAlreadyRunning() {
        var source = ids(501, false);
        source.running = true;
        service.recoverImages(source.imageView());
        assertThat(source.cursor).isEqualTo(500);
        service.recoverImages(source.imageView());
        assertThat(source.cursor).isZero(); assertThat(source.visited).containsExactlyElementsOf(sequence(1, 501));
    }

    @Test void exactlyFullPageRequiresTheFollowingEmptyQueryBeforeReset() {
        var source = ids(100, true);
        service.recoverAnalysis(source);
        assertThat(source.pages).containsExactly(0L, 100L); assertThat(source.cursor).isZero();
    }

    @Test void unexpectedSubmissionFailurePropagatesWithTheCursorAtTheCurrentJob() {
        var source = ids(4, true); source.failAt = 2;
        assertThatThrownBy(() -> service.recoverAnalysis(source)).hasMessage("unexpected submission");
        assertThat(source.cursor).isEqualTo(2); assertThat(source.visited).containsExactly(1L, 2L);
    }

    @Test void queryFailureDoesNotResetAContinuingCursor() {
        IdSource<AnalysisJob> source = mock(IdSource.class);
        when(source.available()).thenReturn(true); when(source.cursor()).thenReturn(200L);
        when(source.page(200, 100)).thenThrow(new IllegalStateException("source failed"));
        assertThatThrownBy(() -> service.recoverAnalysis(source)).hasMessage("source failed");
        verify(source, never()).remember(anyLong());
    }

    @Test void workbookCursorKeepsTimestampAndIdAndAdvancesBeforeTaskChecks() {
        WorkbookSource source = mock(WorkbookSource.class);
        var beginning = new WorkbookCursor(null, ""); var position = new WorkbookCursor(LocalDateTime.of(2026, 1, 1, 12, 0), "same-time-002");
        var job = mock(WorkbookJob.class);
        when(source.cursor()).thenReturn(beginning); when(source.page(beginning, 100)).thenReturn(List.of(job)); when(job.cursor()).thenReturn(position);
        service.recoverWorkbooks(source);
        var order = inOrder(source, job);
        order.verify(source).cursor(); order.verify(source).page(beginning, 100); order.verify(job).cursor();
        order.verify(source).remember(position); order.verify(job).running(); order.verify(job).trySubmit();
        verify(source, never()).remember(beginning);
    }

    @Test void completedShortWorkbookPageResetsTheCompositeCursorButRunningJobsStillAdvanceIt() {
        WorkbookSource source = mock(WorkbookSource.class); WorkbookJob job = mock(WorkbookJob.class);
        var beginning = new WorkbookCursor(null, ""); var position = new WorkbookCursor(LocalDateTime.of(2026, 1, 1, 0, 0), "a");
        when(source.cursor()).thenReturn(beginning); when(source.page(beginning, 100)).thenReturn(List.of(job));
        when(job.cursor()).thenReturn(position); when(job.running()).thenReturn(true);
        service.recoverWorkbooks(source);
        verify(source).remember(position); verify(source).remember(beginning); verify(job, never()).trySubmit();
    }

    private static List<Long> sequence(int start, int end) { return java.util.stream.LongStream.rangeClosed(start, end).boxed().toList(); }
    private static IdFixture ids(int count, boolean reconstructable) { return new IdFixture(count, reconstructable); }

    private static final class IdFixture implements IdSource<AnalysisJob> {
        final int count; final boolean reconstructable;
        final List<Long> pages = new ArrayList<>(), visited = new ArrayList<>();
        long cursor, failAt; boolean running;
        IdFixture(int count, boolean reconstructable) { this.count = count; this.reconstructable = reconstructable; }
        @Override public boolean available() { return true; }
        @Override public long cursor() { return cursor; }
        @Override public void remember(long position) { cursor = position; }
        @Override public List<AnalysisJob> page(long after, int limit) { return jobs(after, limit).stream().map(j -> (AnalysisJob) j).toList(); }
        List<TestJob> jobs(long after, int limit) {
            pages.add(after); return java.util.stream.LongStream.rangeClosed(after + 1, Math.min(count, after + limit)).mapToObj(TestJob::new).toList();
        }
        IdSource<ImageJob> imageView() {
            return new IdSource<>() {
                @Override public boolean available() { return true; }
                @Override public long cursor() { return cursor; }
                @Override public void remember(long position) { cursor = position; }
                @Override public List<ImageJob> page(long after, int limit) { return jobs(after, limit).stream().map(j -> (ImageJob) j).toList(); }
            };
        }
        private final class TestJob implements AnalysisJob, ImageJob {
            final long id;
            TestJob(long id) { this.id = id; }
            @Override public long id() { return id; }
            @Override public boolean running() { visited.add(id); return running; }
            @Override public boolean atCapacity() { return false; }
            @Override public boolean reconstructable() { return reconstructable; }
            @Override public void rejectUnrecoverable() {}
            @Override public boolean tryReserve() { return true; }
            @Override public boolean trySubmit() { if (id == failAt) throw new IllegalStateException("unexpected submission"); return true; }
        }
    }
}
