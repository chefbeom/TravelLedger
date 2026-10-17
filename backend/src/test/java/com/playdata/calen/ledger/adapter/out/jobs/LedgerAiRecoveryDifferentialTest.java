package com.playdata.calen.ledger.adapter.out.jobs;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.common.exception.TooManyRequestsException;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.application.port.out.LedgerAiRecoveryPort.WorkbookCursor;
import com.playdata.calen.ledger.application.service.RecoverLedgerAiJobsService;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.fixture.LedgerAiRecoveryLegacyOracle;
import com.playdata.calen.ledger.repository.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class LedgerAiRecoveryDifferentialTest {
    private final RecoverLedgerAiJobsService service = new RecoverLedgerAiJobsService();
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final int[] SIZES = {0, 1, 99, 100, 101, 499, 500, 501, 620};

    @Test void analysisRecoveryQueryClockLeaseAndSubmissionTracesMatchAcross64Profiles() {
        for (int profile = 0; profile < 64; profile++) {
            var fixture = new Fixture(profile); var repository = mock(LedgerAiAnalysisHistoryRepository.class);
            var rows = fixture.analysisRows();
            when(repository.findAllByStatusAndCreatedAtBeforeAndIdGreaterThanOrderByIdAsc(any(), any(), anyLong(), any())).thenAnswer(call -> {
                long after = call.getArgument(2); fixture.page("analysis", call.getArgument(0), call.getArgument(1), after, call.getArgument(3));
                return rows.stream().filter(row -> row.getId() > after).limit(100).toList();
            });
            var old = new LedgerAiRecoveryLegacyOracle.Analysis();
            old.historyRepository = repository; old.workLeases = fixture.available ? fixture.leases : null; old.ledgerAiTaskExecutor = fixture.available ? new Object() : null;
            old.analysisTasks = fixture.runningLong; old.pendingAnalyses = fixture.pending;
            old.reject = failure -> fixture.rejected(failure.owner(), failure.id(), failure.error());
            old.submit = submission -> { assertThat(submission.recovering()).isTrue(); fixture.submitted(submission.owner(), submission.id()); };
            long initial = fixture.initialId(); old.analysisRecoveryCursor = initial;
            var expected = fixture.capture(old::recoverAnalysisJobs, () -> "" + old.analysisRecoveryCursor);
            old.analysisRecoveryCursor = initial;
            var actual = fixture.capture(() -> service.recoverAnalysis(LedgerAiRecoverySources.analysis(repository, old.workLeases,
                    () -> old.workLeases != null && old.ledgerAiTaskExecutor != null,
                    () -> old.analysisRecoveryCursor, value -> old.analysisRecoveryCursor = value,
                    old.analysisTasks::containsKey, owner -> old.pendingAnalyses.getOrDefault(owner, 0) >= 5,
                    history -> fixture.rejected(history.getOwner().getId(), history.getId(), new com.playdata.calen.common.exception.BadRequestException("이전 서버의 분석 요청은 복구할 수 없습니다. 다시 분석해 주세요.")),
                    history -> fixture.submitted(history.getOwner().getId(), history.getId()))), () -> "" + old.analysisRecoveryCursor);
            assertThat(actual).as("analysis profile %s", profile).isEqualTo(expected);
        }
    }

    @Test void imageRecoveryKeepsLegacyInputReservationAndQueueDifferencesAcross64Profiles() {
        for (int profile = 0; profile < 64; profile++) {
            var fixture = new Fixture(profile); var repository = mock(LedgerImageAnalysisRequestRepository.class);
            var rows = fixture.imageRows();
            when(repository.findAllByStatusAndCreatedAtBeforeAndIdGreaterThanOrderByIdAsc(any(), any(), anyLong(), any())).thenAnswer(call -> {
                long after = call.getArgument(2); fixture.page("images", call.getArgument(0), call.getArgument(1), after, call.getArgument(3));
                return rows.stream().filter(row -> row.getId() > after).limit(100).toList();
            });
            var old = new LedgerAiRecoveryLegacyOracle.Images();
            old.imageAnalysisRequestRepository = repository; old.workLeases = fixture.available ? fixture.leases : null; old.ledgerOcrTaskExecutor = fixture.available ? new Object() : null;
            old.imageAnalysisTasks = fixture.runningLong; old.reserve = fixture::reserved;
            old.reject = failure -> fixture.rejected(failure.owner(), failure.id(), failure.error());
            old.submit = history -> fixture.submitted(history.getOwner().getId(), history.getId());
            long initial = fixture.initialId(); old.imageRecoveryCursor = initial;
            var expected = fixture.capture(old::recoverImageJobs, () -> "" + old.imageRecoveryCursor);
            old.imageRecoveryCursor = initial;
            var actual = fixture.capture(() -> service.recoverImages(LedgerAiRecoverySources.images(repository, old.workLeases,
                    () -> old.ledgerOcrTaskExecutor != null && old.workLeases != null,
                    () -> old.imageRecoveryCursor, value -> old.imageRecoveryCursor = value,
                    old.imageAnalysisTasks::containsKey, history -> history.getImageObjectKey() != null && !history.getImageObjectKey().isBlank(),
                    history -> fixture.rejected(history.getOwner().getId(), history.getId(), new com.playdata.calen.common.exception.BadRequestException("서버 재시작으로 입력을 복구할 수 없습니다. 이미지를 다시 분석해 주세요.")),
                    fixture::reserved, old.submit)), () -> "" + old.imageRecoveryCursor);
            assertThat(actual).as("images profile %s", profile).isEqualTo(expected);
        }
    }

    @Test void workbookRecoveryKeepsTheCompositeCursorAndSingleCutoffAcross64Profiles() {
        for (int profile = 0; profile < 64; profile++) {
            var fixture = new Fixture(profile); var repository = mock(LedgerExcelAnalysisJobRepository.class);
            var rows = fixture.workbookRows();
            when(repository.findRecoveryPage(any(), any(), any(), anyString(), any())).thenAnswer(call -> {
                LocalDateTime after = call.getArgument(2); String id = call.getArgument(3);
                fixture.page("workbooks", call.getArgument(0), call.getArgument(1), after + ":" + id, call.getArgument(4));
                return rows.stream().filter(row -> after == null || row.getCreatedAt().isAfter(after) || row.getCreatedAt().equals(after) && row.getId().compareTo(id) > 0).limit(100).toList();
            });
            var old = new LedgerAiRecoveryLegacyOracle.Workbooks(); old.jobs = repository; old.tasks = fixture.runningString;
            old.submit = job -> fixture.submitted(job.getOwnerId(), Long.parseLong(job.getId()));
            long start = fixture.initialId();
            var initial = start == 0 ? new LedgerAiRecoveryLegacyOracle.RecoveryCursor(null, "") : new LedgerAiRecoveryLegacyOracle.RecoveryCursor(BASE.plusSeconds(start / 3), String.format("%04d", start));
            old.recoveryCursor = initial;
            var expected = fixture.capture(old::recover, () -> old.recoveryCursor.createdAt() + ":" + old.recoveryCursor.id());
            var cursor = new java.util.concurrent.atomic.AtomicReference<>(new WorkbookCursor(initial.createdAt(), initial.id()));
            var actual = fixture.capture(() -> service.recoverWorkbooks(LedgerAiRecoverySources.workbooks(repository,
                    cursor::get, cursor::set, old.tasks::containsKey, old.submit)), () -> cursor.get().createdAt() + ":" + cursor.get().id());
            assertThat(actual).as("workbooks profile %s", profile).isEqualTo(expected);
        }
    }

    private record Outcome(List<String> trace, String cursor, String failure) {}

    private static final class Fixture {
        final int profile; final boolean available;
        final List<String> trace = new ArrayList<>();
        final WorkLeaseService leases = mock(WorkLeaseService.class);
        final Map<Long, Object> runningLong = new HashMap<>() {
            @Override public boolean containsKey(Object key) { trace.add("running:" + key); return super.containsKey(key); }
        };
        final Map<String, Object> runningString = new HashMap<>() {
            @Override public boolean containsKey(Object key) { trace.add("running:" + key); return super.containsKey(key); }
        };
        final Map<Long, Integer> pending = new HashMap<>() {
            @Override public Integer getOrDefault(Object key, Integer fallback) { trace.add("capacity:" + key); return super.getOrDefault(key, fallback); }
        };
        Fixture(int profile) {
            this.profile = profile; available = profile % 13 != 0;
            for (long id = 1; id <= 620; id++) if (profile % 4 == 1 && id % 6 == 0) { runningLong.put(id, new Object()); runningString.put(String.format("%04d", id), new Object()); }
            if (profile % 4 == 2) pending.put(2L, 5);
            when(leases.tryAcquire(anyString(), any())).thenAnswer(call -> {
                String key = call.getArgument(0); Duration ttl = call.getArgument(1); trace.add("lease:" + key + ":" + ttl);
                long id = Long.parseLong(key.substring(key.lastIndexOf(':') + 1));
                if (profile % 8 == 5 && id == 10) throw new IllegalStateException("lease unavailable");
                if (profile % 2 == 0 && id % 7 == 0) return null;
                var lease = mock(WorkLeaseService.Lease.class); doAnswer(c -> { trace.add("close:" + key); return null; }).when(lease).close(); return lease;
            });
        }
        int count() { return SIZES[profile % SIZES.length]; }
        long initialId() { return profile % 3 == 0 ? 250 : 0; }
        void page(String kind, Object status, Object before, Object after, Pageable page) {
            trace.add("page:" + kind + ":" + status + ":" + before + ":" + after + ":" + page.getPageNumber() + ":" + page.getPageSize());
            if (profile % 16 == 6 && after instanceof Long id && id >= 100) throw new IllegalStateException("query unavailable");
        }
        void rejected(Long owner, Long id, RuntimeException failure) { trace.add("reject:" + owner + ":" + id + ":" + failure.getClass().getSimpleName() + ":" + failure.getMessage()); }
        void reserved(long owner) {
            trace.add("reserve:" + owner);
            if (profile % 4 == 2 && owner == 2) throw new TooManyRequestsException("owner full");
        }
        void submitted(Long owner, Long id) {
            trace.add("submit:" + owner + ":" + id);
            if (profile % 8 == 3 && id >= 12 && id % 5 == 2) throw new TooManyRequestsException("queue full");
            if (profile % 8 == 4 && id == 13) throw new IllegalStateException("unexpected submission");
        }
        Outcome capture(Runnable action, Supplier<String> cursor) {
            trace.clear(); int[] calls = {0}; String failure = null;
            try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
                clock.when(LocalDateTime::now).thenAnswer(call -> { int index = calls[0]++; trace.add("clock:" + index); return BASE.plusHours(1).plusSeconds(index); });
                try { action.run(); } catch (RuntimeException error) { failure = error.getClass().getName() + ":" + error.getMessage(); }
            }
            return new Outcome(List.copyOf(trace), cursor.get(), failure);
        }
        AppUser owner(long id) { var owner = new AppUser(); owner.setId(1 + id % 4); return owner; }
        List<LedgerAiAnalysisHistory> analysisRows() {
            return java.util.stream.LongStream.rangeClosed(1, count()).mapToObj(id -> {
                var row = new LedgerAiAnalysisHistory(); row.setId(id); row.setOwner(owner(id)); row.setMode(LedgerAiAnalysisMode.PERIOD); row.setPeriodType(LedgerAiAnalysisPeriod.MONTH);
                row.setFromDate(BASE.toLocalDate()); row.setToDate(BASE.toLocalDate().plusDays(30)); row.setFocusPrompt(id % 5 == 0 ? null : id % 7 == 0 ? "" : "focus"); return row;
            }).toList();
        }
        List<LedgerImageAnalysisRequest> imageRows() {
            return java.util.stream.LongStream.rangeClosed(1, count()).mapToObj(id -> {
                var row = new LedgerImageAnalysisRequest(); row.setId(id); row.setOwner(owner(id));
                row.setImageObjectKey(id % 5 == 0 ? null : id % 7 == 0 ? " " : "fixture/" + id); row.setEffectivePrompt(id % 11 == 0 ? null : ""); row.setDocumentType("AUTO"); return row;
            }).toList();
        }
        List<LedgerExcelAnalysisJob> workbookRows() {
            return java.util.stream.LongStream.rangeClosed(1, count()).mapToObj(id -> {
                var row = new LedgerExcelAnalysisJob(); row.setId(String.format("%04d", id)); row.setOwnerId(1 + id % 4); row.setCreatedAt(BASE.plusSeconds(id / 3)); return row;
            }).toList();
        }
    }
}
