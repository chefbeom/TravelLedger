package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.*;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.adapter.out.jobs.LedgerAnalysisAdmissions;
import com.playdata.calen.ledger.adapter.support.*;
import com.playdata.calen.ledger.application.port.in.ManageLedgerAnalysisHistoryUseCase;
import com.playdata.calen.ledger.application.service.StartLedgerAnalysisService;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.*;
import com.playdata.calen.ledger.domain.policy.LedgerAnalysisAdmissionPolicy;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class LedgerAnalysisAdmissionDifferentialTest {
    static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 15, 12, 0);

    @Test void asyncAdmissionGuardsKeysClockReuseDraftSubmissionAndErrorsMatchAcross128Profiles() throws Exception {
        for (int profile = 0; profile < 128; profile++) {
            var fixture = new Fixture(profile); var oldPlan = plan(profile); var modern = modern(oldPlan);
            var expected = fixture.capture(() -> fixture.old.startResolvedAnalysis(7L, oldPlan));
            var actual = fixture.capture(() -> LedgerAnalysisHistoryMapper.response(new StartLedgerAnalysisService().start(
                    LedgerAnalysisAdmissions.operation(7L, modern, fixture.users, fixture.properties, fixture.metrics,
                            fixture.distributed ? fixture.leases : null, fixture.rows, fixture.queries, fixture::submit, fixture::failed), Duration.ofMinutes(5))));
            assertThat(actual).as("admission profile %s", profile).isEqualTo(expected);
        }
    }
    @Test void planStringTitleAndAllDraftFieldsMatchTheFrozenBuilderAcross64Plans() throws Exception {
        for (int profile = 0; profile < 64; profile++) {
            var fixture = new Fixture(profile); var oldPlan = plan(profile); var modern = modern(oldPlan);
            assertThat(modern.toString()).isEqualTo(oldPlan.toString());
            assertThat(LedgerAnalysisAdmissionPolicy.buildTitle(modern)).isEqualTo(fixture.old.buildTitle(oldPlan));
            var oldDraft = fixture.old.baseHistory(fixture.owner, oldPlan);
            var newDraft = LedgerAnalysisHistoryDrafts.create(fixture.owner, modern, fixture.properties, fixture.metrics);
            assertThat(fixture.describe(newDraft)).isEqualTo(fixture.describe(oldDraft));
            assertThat(newDraft.getOwner()).isSameAs(fixture.owner);
        }
    }
    @Test void synchronousExecutorStyleMutationIsVisibleOnlyAfterSubmissionNotAnEarlySummarySnapshot() throws Exception {
        var fixture = new Fixture(9); fixture.properties.setEnabled(true);
        var oldPlan = new LedgerAnalysisAdmissionLegacyOracle.AnalysisPlan(LedgerAiAnalysisMode.PERIOD, LedgerAiAnalysisPeriod.MONTH, null,
                new LedgerAnalysisAdmissionLegacyOracle.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)), null, "custom");
        var result = new StartLedgerAnalysisService().start(LedgerAnalysisAdmissions.operation(7L, modern(oldPlan), fixture.users,
                fixture.properties, fixture.metrics, null, fixture.rows, fixture.queries, fixture::submit, fixture::failed), Duration.ofMinutes(5));
        assertThat(result.history().status()).isEqualTo(LedgerAiAnalysisStatus.COMPLETED);
        assertThat(result.history().summary()).isEqualTo("changed while submitting"); assertThat(result.result()).isNull();
    }

    private static LedgerAnalysisAdmissionLegacyOracle.AnalysisPlan plan(int seed) {
        var primary = new LedgerAnalysisAdmissionLegacyOracle.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        var compare = seed % 3 == 0 ? null : new LedgerAnalysisAdmissionLegacyOracle.DateRange(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31));
        String focus = new String[]{null, "", " \u2003 ", "fixture focus", "여행📍\nA|B"}[seed % 5];
        return new LedgerAnalysisAdmissionLegacyOracle.AnalysisPlan(seed % 11 == 0 ? null : LedgerAiAnalysisMode.values()[seed % 2],
                LedgerAiAnalysisPeriod.values()[seed % 6], LedgerAiComparisonPreset.values()[seed % 6], primary, compare, focus);
    }
    private static LedgerAnalysisAdmissionData.AnalysisPlan modern(LedgerAnalysisAdmissionLegacyOracle.AnalysisPlan p) {
        var compare = p.comparisonRange();
        return new LedgerAnalysisAdmissionData.AnalysisPlan(p.mode(), p.periodType(), p.comparisonPreset(),
                LedgerAnalysisPreparationMapper.range(p.primaryRange().from(), p.primaryRange().to()),
                compare == null ? null : LedgerAnalysisPreparationMapper.range(compare.from(), compare.to()), p.focusPrompt());
    }
    private record Outcome(String value, String failure, List<String> trace) {}
    private static final class Fixture {
        final int profile; final boolean distributed;
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final List<String> trace = new ArrayList<>();
        final AppUser owner = new AppUser();
        final AppUserService users = mock(AppUserService.class);
        final LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        final LedgerAiAnalysisMetrics metrics = mock(LedgerAiAnalysisMetrics.class);
        final WorkLeaseService leases = mock(WorkLeaseService.class);
        final LedgerAiAnalysisHistoryRepository rows = mock(LedgerAiAnalysisHistoryRepository.class);
        final ManageLedgerAnalysisHistoryUseCase queries = mock(ManageLedgerAnalysisHistoryUseCase.class);
        final AtomicReference<LedgerAiAnalysisHistory> pending = new AtomicReference<>();
        final LedgerAnalysisAdmissionLegacyOracle old;
        Fixture(int profile) throws Exception {
            this.profile = profile; distributed = profile % 4 != 0; owner.setId(7L);
            properties.setEnabled(profile % 19 != 0); properties.setProvider("lmstudio"); properties.setLmStudioBaseUrl("http://localhost:1234"); properties.setModel("model-" + profile % 3);
            when(users.getRequiredUser(7L)).thenAnswer(call -> { trace.add("owner:7"); if (profile % 17 == 0) throw new NotFoundException("owner unavailable"); return owner; });
            when(metrics.providerLabel()).thenAnswer(call -> { trace.add("provider"); return "lmstudio"; });
            when(leases.tryAcquire(anyString(), any())).thenAnswer(call -> {
                String key = call.getArgument(0); trace.add("lease:" + key + ":" + call.getArgument(1));
                if (profile % 23 == 0) throw new IllegalStateException("lease unavailable");
                if (profile % 5 == 0) return null;
                var lease = mock(WorkLeaseService.Lease.class);
                doAnswer(c -> { trace.add("close:" + key); if (profile % 31 == 0) throw new IllegalStateException("lease close failed"); return null; }).when(lease).close(); return lease;
            });
            var header = new Summary(88L, LedgerAiAnalysisMode.PERIOD, LedgerAiAnalysisPeriod.MONTH, null, LedgerAiAnalysisStatus.PROCESSING,
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, null, "model", "title", "summary", null, NOW);
            var stored = new StoredHistory(header, "fixture result");
            var cached = LedgerAnalysisHistoryMapper.facts(json.readValue("{\"historyId\":88,\"summary\":\"cached\",\"totalExpense\":12.3400}", com.playdata.calen.ledger.dto.LedgerAiAnalysisResponse.class));
            when(queries.processing(any())).thenAnswer(call -> { trace.add("processing:" + call.getArgument(0)); return profile % 6 == 0 ? Optional.of(stored) : Optional.empty(); });
            when(queries.completed(any())).thenAnswer(call -> { trace.add("completed:" + call.getArgument(0)); return profile % 6 == 1 || profile % 6 == 2 ? Optional.of(stored) : Optional.empty(); });
            when(queries.readReusable(any())).thenAnswer(call -> { trace.add("decode"); return profile % 6 == 1 ? cached : null; });
            when(rows.save(any())).thenAnswer(call -> {
                LedgerAiAnalysisHistory draft = call.getArgument(0); assertThat(draft.getOwner()).isSameAs(owner);
                trace.add("draft:" + describe(draft)); if (profile % 11 == 0) throw new IllegalStateException("save unavailable");
                draft.setId(77L); draft.setCreatedAt(NOW); pending.set(draft); return draft;
            });
            old = new LedgerAnalysisAdmissionLegacyOracle(users, properties, metrics, distributed ? leases : null, rows, queries, this::submit, this::failed);
        }
        void submit(long id) {
            trace.add("submit:" + id);
            if (profile % 7 == 0) throw new TooManyRequestsException("queue full", 30);
            if (profile % 13 == 0) throw new IllegalStateException("submit unavailable");
            if (profile % 9 == 0) { pending.get().setStatus(LedgerAiAnalysisStatus.COMPLETED); pending.get().setSummary("changed while submitting"); }
        }
        void failed(Long id, RuntimeException failure) {
            trace.add("failed:" + id + ":" + failure.getClass().getSimpleName() + ":" + failure.getMessage());
            if (profile % 14 == 0) throw new IllegalArgumentException("finalization unavailable");
        }
        String describe(LedgerAiAnalysisHistory row) throws Exception {
            return json.writeValueAsString(LedgerAnalysisHistoryMapper.response(LedgerAnalysisHistoryMapper.summary(row)))
                    + ":focus=" + row.getFocusPrompt() + ":provider=" + row.getProvider() + ":owner=" + row.getOwner().getId();
        }
        Outcome capture(Supplier<?> action) throws Exception {
            trace.clear(); pending.set(null); String value = null, failure = null;
            try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
                clock.when(LocalDateTime::now).thenAnswer(call -> { trace.add("clock"); return NOW; });
                try { value = json.writeValueAsString(action.get()); }
                catch (RuntimeException error) { failure = error.getClass().getName() + ":" + error.getMessage() + (error instanceof TooManyRequestsException limited ? ":retry=" + limited.getRetryAfterSeconds() : ""); }
            }
            return new Outcome(value, failure, List.copyOf(trace));
        }
    }
}
