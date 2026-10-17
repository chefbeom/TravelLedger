package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.*;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisBoundaryMapper;
import com.playdata.calen.ledger.adapter.out.analysis.LedgerAnalysisFormats;
import com.playdata.calen.ledger.adapter.out.observability.LedgerAnalysisWorkTelemetry;
import com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerAnalysisWorkStore;
import com.playdata.calen.ledger.adapter.support.LedgerAnalysisPreparationMapper;
import com.playdata.calen.ledger.application.port.in.*;
import com.playdata.calen.ledger.application.port.out.LedgerSpendingAnalysisPort;
import com.playdata.calen.ledger.application.service.ExecuteLedgerAnalysisService;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData;
import com.playdata.calen.ledger.dto.*;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LedgerAnalysisExecutionDifferentialTest {
    static final LocalDateTime LOCAL = LocalDateTime.of(2026, 1, 15, 12, 0);
    static final Instant INSTANT = Instant.parse("2026-01-15T03:00:00Z");

    @Test void workerStatusSerializationProviderLeaseTerminalMetricsAndErrorsMatchAcross128Profiles() throws Exception {
        for (int profile = 0; profile < 128; profile++) {
            var fixture = new Fixture(profile, true);
            var expected = fixture.capture(() -> { fixture.old.processAnalysisInBackground(7L, 9L, fixture.plan, fixture.lease); return null; });
            var actual = fixture.capture(() -> { fixture.worker.process(7L, 9L, fixture.modernPlan, () -> fixture.lease == null || fixture.lease.isValid()); return null; });
            assertThat(actual).as("worker profile %s", profile).isEqualTo(expected);
        }
    }
    @Test void completePreparedV2AndResponseJsonMatchAcross128DatasetsWithoutCapsOnLocalReports() throws Exception {
        for (int profile = 0; profile < 128; profile++) {
            var fixture = new Fixture(profile, false);
            var expected = fixture.capture(() -> List.of(fixture.old.buildPayload(fixture.plan, fixture.data), fixture.old.buildResponse(9L, fixture.plan, fixture.data, fixture.remote)));
            var modernData = LedgerAnalysisFormats.dataset(fixture.facts);
            var actual = fixture.capture(() -> List.of(fixture.formats.buildPayload(fixture.modernPlan, modernData), fixture.formats.buildResponse(9L, fixture.modernPlan, modernData, fixture.remote)));
            assertThat(actual).as("format profile %s", profile).isEqualTo(expected);
        }
    }
    @Test void preparationDefersSerializationAndRequestMappingAndPreserves200120ProviderVersus205LocalRows() {
        var fixture = new Fixture(5, false);
        var prepared = fixture.formats.prepare(fixture.modernPlan, fixture.facts);
        verify(fixture.codec, never()).write(any());
        var payload = fixture.formats.buildPayload(fixture.modernPlan, LedgerAnalysisFormats.dataset(fixture.facts));
        assertThat(payload.expenseEntries()).hasSize(200); assertThat(payload.comparisonExpenseEntries()).hasSize(120);
        assertThat(prepared.request()).isEqualTo(LedgerAnalysisBoundaryMapper.toRequest(payload));
        assertThat(prepared.serialize()).isNotBlank(); verify(fixture.codec).write(any());
        var response = fixture.formats.response(9L, fixture.modernPlan, fixture.facts, fixture.remote);
        assertThat(response.expenseEntryCount()).isEqualTo(205); assertThat(response.report().fullReport()).contains("지출 거래는 205건");
    }

    private record Outcome(String value, String error, List<String> trace) {}
    private static final class Fixture {
        final int profile; final boolean faults;
        final List<String> trace = new ArrayList<>();
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        final LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        final LedgerAiAnalysisHistoryRepository rows = mock(LedgerAiAnalysisHistoryRepository.class);
        final LedgerAiAnalysisTextSanitizer text = new LedgerAiAnalysisTextSanitizer();
        final LedgerAiAnalysisPayloadBuilder payloads = spy(new LedgerAiAnalysisPayloadBuilder(text));
        final LedgerAiAnalysisJsonCodec codec = spy(new LedgerAiAnalysisJsonCodec(json));
        final LedgerAiAnalysisMetrics metrics = mock(LedgerAiAnalysisMetrics.class);
        final LedgerSpendingAnalysisPort provider = mock(LedgerSpendingAnalysisPort.class);
        final FinalizeLedgerAiJobUseCase finalizer = mock(FinalizeLedgerAiJobUseCase.class);
        final Timer.Sample timer = mock(Timer.Sample.class);
        final LedgerAnalysisData.Result remote = mock(LedgerAnalysisData.Result.class);
        final WorkLeaseService.Lease lease;
        final LedgerAnalysisExecutionLegacyOracle.AnalysisPlan plan;
        final LedgerAnalysisAdmissionData.AnalysisPlan modernPlan;
        final LedgerAnalysisExecutionLegacyOracle.AnalysisDataset data;
        final LedgerAnalysisPreparationData.Dataset facts;
        final LedgerAnalysisFormats formats;
        final LedgerAnalysisExecutionLegacyOracle old;
        final ExecuteLedgerAnalysisService worker;
        Fixture(int profile, boolean faults) {
            this.profile = profile; this.faults = faults;
            properties.setModel("model-" + profile % 3);
            var from = LocalDate.of(2026, 1, 1); var to = from.plusDays(30);
            var compare = profile % 2 == 0 ? null : new LedgerAnalysisExecutionLegacyOracle.DateRange(from.minusMonths(1), to.minusMonths(1));
            plan = new LedgerAnalysisExecutionLegacyOracle.AnalysisPlan(LedgerAiAnalysisMode.values()[profile % 2], LedgerAiAnalysisPeriod.values()[profile % 6],
                    profile % 3 == 0 ? LedgerAiComparisonPreset.WEEKLY_DIGEST : LedgerAiComparisonPreset.CUSTOM,
                    new LedgerAnalysisExecutionLegacyOracle.DateRange(from, to), compare, "fixture focus");
            modernPlan = new LedgerAnalysisAdmissionData.AnalysisPlan(plan.mode(), plan.periodType(), plan.comparisonPreset(), LedgerAnalysisPreparationMapper.range(from, to),
                    compare == null ? null : LedgerAnalysisPreparationMapper.range(compare.from(), compare.to()), plan.focusPrompt());
            var expenses = expenses(profile % 5 == 0 ? 205 : profile % 7, from);
            var comparisonExpenses = compare == null ? List.<com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData.ExpenseEntryPayload>of() : expenses(expenses.size() == 205 ? 123 : 3, compare.from());
            var amount = new BigDecimal("1234.5600");
            var overview = new OverviewResponse(from, to, BigDecimal.ZERO, amount, amount.negate(), expenses.size());
            var categories = List.of(new CategoryBreakdownItemResponse("Group", "Detail", amount, expenses.size()));
            var payments = List.of(new PaymentBreakdownItemResponse("Card", PaymentMethodKind.CARD, amount, expenses.size()));
            data = new LedgerAnalysisExecutionLegacyOracle.AnalysisDataset(overview, categories, payments,
                    List.of(new PeriodComparisonItemResponse("Period", from, to, BigDecimal.ZERO, amount, amount.negate())), expenses, expenses.stream().limit(20).toList(),
                    compare == null ? null : new OverviewResponse(compare.from(), compare.to(), BigDecimal.ZERO, amount, amount.negate(), comparisonExpenses.size()),
                    compare == null ? List.of() : categories, compare == null ? List.of() : payments, comparisonExpenses);
            facts = new LedgerAnalysisPreparationData.Dataset(LedgerAnalysisPreparationMapper.overview(overview), LedgerAnalysisPreparationMapper.categoryFacts(categories), LedgerAnalysisPreparationMapper.paymentFacts(payments),
                    LedgerAnalysisPreparationMapper.periodFacts(data.periodComparison()), facts(expenses), facts(data.topExpenses()), LedgerAnalysisPreparationMapper.overview(data.comparisonOverview()),
                    LedgerAnalysisPreparationMapper.categoryFacts(data.comparisonCategoryBreakdown()), LedgerAnalysisPreparationMapper.paymentFacts(data.comparisonPaymentBreakdown()), facts(comparisonExpenses));
            lease = profile % 4 == 0 ? null : mock(WorkLeaseService.Lease.class);
            if (lease != null) when(lease.isValid()).thenAnswer(call -> { trace.add("lease"); return profile % 5 != 0; });
            when(rows.findByIdAndOwnerId(9L, 7L)).thenAnswer(call -> {
                trace.add("load:9:7"); if (faults && profile % 29 == 0) throw new IllegalStateException("lookup unavailable");
                if (faults && profile % 17 == 0) return Optional.empty();
                var row = new LedgerAiAnalysisHistory(); row.setId(9L); row.setStatus(faults && profile % 19 == 0 ? LedgerAiAnalysisStatus.COMPLETED : faults && profile % 23 == 0 ? null : LedgerAiAnalysisStatus.PROCESSING); return Optional.of(row);
            });
            when(rows.saveProcessingPayload(any(), any(), any())).thenAnswer(call -> { trace.add("save:" + call.getArgument(0) + ":" + call.getArgument(1) + ":" + call.getArgument(2)); if (stage() == 4) throw new IllegalStateException("input save unavailable"); return stage() == 5 ? 0 : 1; });
            doAnswer(call -> { trace.add("minimize"); if (stage() == 2) throw new IllegalStateException("input build unavailable"); return call.callRealMethod(); }).when(payloads).buildPayloadMinimizationSummary(anyInt(), anyInt(), anyList(), anyList());
            doAnswer(call -> { Object value = call.getArgument(0); boolean input = value instanceof com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData.LedgerAiN8nPayload; trace.add("serialize:" + (input ? "input" : "result")); if (input && stage() == 3 || !input && stage() == 8) throw new IllegalStateException("token=fixture-secret https://example.invalid"); return call.callRealMethod(); }).when(codec).write(any());
            when(metrics.startAiRequestTimer()).thenAnswer(call -> { trace.add("start"); if (stage() == 6) throw new IllegalStateException("timer unavailable"); return profile % 2 == 0 ? null : timer; });
            doAnswer(call -> { trace.add("metric:" + (call.getArgument(0) == null ? "null" : "sample") + ":" + call.getArgument(1)); if (stage() == 11 && "success".equals(call.getArgument(1))) throw new IllegalStateException("metric unavailable"); return null; }).when(metrics).recordAiRequest(any(), anyString());
            when(remote.summary()).thenReturn(profile % 3 == 0 ? null : " raw remote summary ");
            when(provider.analyze(any())).thenAnswer(call -> { trace.add("provider:" + json.writeValueAsString(call.getArgument(0))); if (stage() == 7 || stage() == 12) throw new IllegalStateException("token=fixture-secret https://example.invalid"); return remote; });
            when(finalizer.complete(any())).thenAnswer(call -> { trace.add("complete:" + call.getArgument(0)); if (stage() == 9) throw new IllegalStateException("complete unavailable"); return stage() != 10; });
            when(finalizer.fail(any())).thenAnswer(call -> { trace.add("fail:" + call.getArgument(0)); if (stage() == 12) throw new IllegalStateException("failure state unavailable"); return true; });
            var merger = new LedgerAiAnalysisReportMerger(text);
            formats = new LedgerAnalysisFormats(properties, payloads, codec, text, merger);
            old = new LedgerAnalysisExecutionLegacyOracle(rows, properties, provider, metrics, codec, text, payloads, merger, finalizer,
                    (owner, value) -> { datasetTrace(owner); return data; });
            var datasets = mock(PrepareLedgerAnalysisDatasetUseCase.class);
            when(datasets.prepare(any(), any())).thenAnswer(call -> { datasetTrace(call.getArgument(0)); return facts; });
            worker = new ExecuteLedgerAnalysisService(datasets, new JpaLedgerAnalysisWorkStore(rows), formats, provider, finalizer, new LedgerAnalysisWorkTelemetry(metrics), LocalDateTime::now);
        }
        int stage() { return faults ? profile % 14 : -1; }
        void datasetTrace(Long owner) { trace.add("dataset:" + owner); if (stage() == 1) throw new IllegalStateException("dataset unavailable"); }
        static List<com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData.ExpenseEntryPayload> expenses(int count, LocalDate start) {
            return java.util.stream.IntStream.range(0, count).mapToObj(i -> new com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData.ExpenseEntryPayload(start.plusDays(i % 10), "place " + i % 4,
                    "memo\nhttps://example.invalid", new BigDecimal("1.2300"), "Group", "Detail", "Card")).toList();
        }
        static List<LedgerAnalysisData.ExpenseEntryPayload> facts(List<com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData.ExpenseEntryPayload> values) {
            return values.stream().map(v -> new LedgerAnalysisData.ExpenseEntryPayload(v.entryDate(), v.title(), v.memo(), v.amount(), v.categoryGroupName(), v.categoryDetailName(), v.paymentMethodName())).toList();
        }
        Outcome capture(Supplier<?> action) throws Exception {
            trace.clear(); String value = null, error = null; long thread = Thread.currentThread().getId();
            Logger logger = (Logger) LoggerFactory.getLogger("com.playdata.calen.ledger.ai.LedgerAiAnalysisService"); boolean additive = logger.isAdditive();
            var appender = new AppenderBase<ILoggingEvent>() { @Override protected void append(ILoggingEvent event) { if (Thread.currentThread().getId() == thread) trace.add("warn:" + event.getFormattedMessage()); } };
            appender.start(); logger.addAppender(appender); logger.setAdditive(false);
            try (var instant = mockStatic(Instant.class, CALLS_REAL_METHODS); var local = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
                instant.when(Instant::now).thenAnswer(call -> { trace.add("instant"); return INSTANT; });
                local.when(LocalDateTime::now).thenAnswer(call -> { trace.add("local"); return LOCAL; });
                try { value = json.writeValueAsString(action.get()); } catch (RuntimeException failure) { error = failure.getClass().getName() + ":" + failure.getMessage(); }
            } finally { logger.detachAppender(appender); logger.setAdditive(additive); appender.stop(); }
            return new Outcome(value, error, List.copyOf(trace));
        }
    }
}
