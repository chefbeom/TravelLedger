package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.ledger.adapter.out.persistence.LedgerAnalysisHistoryQueryAdapter;
import com.playdata.calen.ledger.adapter.out.serialization.LedgerAnalysisHistoryJsonAdapter;
import com.playdata.calen.ledger.adapter.support.LedgerAnalysisHistoryMapper;
import com.playdata.calen.ledger.application.service.ManageLedgerAnalysisHistoryService;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.*;
import com.playdata.calen.ledger.dto.*;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;

class LedgerAnalysisHistoryDifferentialTest {
    static final Instant NOW = Instant.parse("2026-03-09T01:02:03Z");
    static final String[] JSON = {null, " \u2003 ", "broken", "null", "{}",
            "{\"historyId\":99,\"totalExpense\":12.34567,\"summary\":\" raw \",\"highlights\":[null,\"\",\" kept \"],\"categoryBreakdown\":[{\"groupName\":null,\"detailName\":\" detail \",\"totalAmount\":1.2300,\"entryCount\":2}]}",
            "{\"historyId\":99,\"mode\":\"COMPARISON\",\"periodType\":\"CUSTOM\",\"comparisonPreset\":\"CUSTOM\",\"generatedAt\":\"2026-01-01T01:02:03Z\",\"averageDailyExpense\":0.0000,\"paymentBreakdown\":[{\"paymentMethodName\":\" card \",\"kind\":\"CARD\",\"totalAmount\":2.300,\"entryCount\":1}],\"periodComparison\":[{\"label\":\" raw \",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-01-02\",\"income\":null,\"expense\":3.0000,\"balance\":-3.0000}],\"report\":{\"keySummary\":\" raw report \",\"notableSpending\":[null,\"\",\" kept \"],\"improvementActions\":[\" action \"]}}"};

    @Test void fullStoredAndReusableJsonAndCodecDecisionsMatchAcross128Rows() throws Exception {
        for (int seed = 0; seed < 128; seed++) {
            var fixture = new Fixture(seed); var history = history(seed);
            try (var clock = mockStatic(Instant.class, CALLS_REAL_METHODS); var zones = mockStatic(ZoneId.class, CALLS_REAL_METHODS)) {
                clock.when(Instant::now).thenReturn(NOW); zones.when(ZoneId::systemDefault).thenReturn(fixture.zone);
                fixture.check(() -> fixture.old.readStoredHistoryResultOrFallback(history),
                        () -> LedgerAnalysisHistoryMapper.response(fixture.core.readStored(LedgerAnalysisHistoryMapper.stored(history))));
                fixture.check(() -> fixture.old.readReusableStoredHistoryResult(history),
                        () -> LedgerAnalysisHistoryMapper.response(fixture.core.readReusable(LedgerAnalysisHistoryMapper.stored(history))));
            }
        }
        var fixture = new Fixture(0);
        fixture.check(() -> fixture.old.readStoredHistoryResultOrFallback(null), () -> LedgerAnalysisHistoryMapper.response(fixture.core.readStored(null)));
    }

    @Test void searchOwnerValidationDateBoundsPaginationAndRepositoryMetadataMatchAcross96Filters() throws Exception {
        for (int seed = 0; seed < 96; seed++) {
            var fixture = new Fixture(seed); var row = history(seed);
            @SuppressWarnings("unchecked") Page<LedgerAiAnalysisHistory> page = mock(Page.class);
            when(page.getContent()).thenReturn(List.of(row)); when(page.getNumber()).thenReturn(7); when(page.getSize()).thenReturn(3);
            when(page.getTotalElements()).thenReturn(29L); when(page.getTotalPages()).thenReturn(10);
            when(fixture.rows.searchHistories(any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
                fixture.trace.add("search:" + arguments(call.getArguments())); return page;
            });
            var mode = seed % 3 == 0 ? null : LedgerAiAnalysisMode.values()[seed % 2];
            var period = seed % 4 == 0 ? null : LedgerAiAnalysisPeriod.values()[seed % 6];
            LocalDate from = seed % 3 == 0 ? null : LocalDate.of(2026, 1, 2);
            LocalDate to = seed % 5 == 0 ? null : seed % 7 == 0 ? LocalDate.MAX : seed % 4 == 0 ? LocalDate.of(2026, 1, 1) : LocalDate.of(2026, 1, 3);
            Boolean comparison = seed % 3 == 0 ? null : seed % 3 == 1;
            int requestedPage = seed % 4 - 2, size = new int[]{-3, 0, 1, 50, 99, Integer.MAX_VALUE}[seed % 6];
            if (seed % 11 == 0) doAnswer(call -> { fixture.trace.add("owner:" + call.getArgument(0)); throw new com.playdata.calen.common.exception.NotFoundException("owner missing"); }).when(fixture.users).getRequiredUser(7L);
            fixture.check(() -> fixture.old.getHistories(7L, mode, period, from, to, comparison, requestedPage, size),
                    () -> LedgerAnalysisHistoryMapper.response(fixture.core.search(7L, new Filter(mode, period, from, to, comparison, requestedPage, size))));
        }
    }

    @Test void completedProcessingLookupAndDeleteContractsMatchAcross64Keys() throws Exception {
        for (int seed = 0; seed < 64; seed++) {
            var fixture = new Fixture(seed); var row = history(seed);
            var found = seed % 3 == 0 ? Optional.<LedgerAiAnalysisHistory>empty() : Optional.of(row);
            when(fixture.rows.findLatestMatchingCompletedAnalysis(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> { fixture.trace.add("completed:" + arguments(call.getArguments())); return found; });
            when(fixture.rows.findLatestMatchingProcessingAnalysis(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> { fixture.trace.add("processing:" + arguments(call.getArguments())); return found; });
            var range = new LedgerAnalysisHistoryLegacyOracle.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
            var compare = seed % 2 == 0 ? null : new LedgerAnalysisHistoryLegacyOracle.DateRange(range.from().minusMonths(1), range.to().minusMonths(1));
            var plan = new LedgerAnalysisHistoryLegacyOracle.AnalysisPlan(LedgerAiAnalysisMode.values()[seed % 2], LedgerAiAnalysisPeriod.values()[seed % 6], LedgerAiComparisonPreset.values()[seed % 6], range, compare, "not-a-query-key");
            var since = seed % 2 == 0 ? null : LocalDateTime.of(2026, 1, 2, 3, 4, 5);
            var key = new MatchKey(7L, fixture.metrics.providerLabel(), fixture.properties.getModel(), plan.mode(), plan.periodType(), range.from(), range.to(), compare == null ? null : compare.from(), compare == null ? null : compare.to(), since);
            fixture.check(() -> fixture.old.findLatestMatchingAnalysis(7L, plan, since).map(fixture.old::toSummary).orElse(null),
                    () -> fixture.core.completed(key).map(value -> LedgerAnalysisHistoryMapper.response(value.header())).orElse(null));
            fixture.check(() -> fixture.old.findLatestMatchingProcessingAnalysis(7L, plan, since).map(fixture.old::toSummary).orElse(null),
                    () -> fixture.core.processing(key).map(value -> LedgerAnalysisHistoryMapper.response(value.header())).orElse(null));
            int count = new int[]{0, 1, 3, -1}[seed % 4];
            when(fixture.rows.deleteByIdAndOwnerId(9L, 7L)).thenAnswer(call -> { fixture.trace.add("delete:" + arguments(call.getArguments())); return count; });
            when(fixture.rows.deleteAllByOwnerId(7L)).thenAnswer(call -> { fixture.trace.add("delete-all:" + arguments(call.getArguments())); return count; });
            fixture.check(() -> fixture.old.deleteHistory(7L, 9L), () -> new LedgerAiAnalysisHistoryDeleteResponse(fixture.core.delete(7L, 9L)));
            fixture.check(() -> fixture.old.deleteHistories(7L), () -> new LedgerAiAnalysisHistoryDeleteResponse(fixture.core.deleteAll(7L)));
        }
    }

    private static String arguments(Object[] values) {
        return Arrays.stream(values).map(value -> value instanceof Limit limit ? "limit=" + limit.max() : value instanceof Pageable page ? "page=" + page.getPageNumber() + ",size=" + page.getPageSize() : String.valueOf(value)).toList().toString();
    }
    private static LedgerAiAnalysisHistory history(int seed) {
        var history = new LedgerAiAnalysisHistory(); history.setId(90L + seed);
        history.setMode(seed % 2 == 0 ? null : LedgerAiAnalysisMode.COMPARISON); history.setPeriodType(LedgerAiAnalysisPeriod.values()[seed % 6]);
        history.setComparisonPreset(seed % 2 == 0 ? null : LedgerAiComparisonPreset.CUSTOM);
        history.setStatus(seed % 4 == 0 ? null : LedgerAiAnalysisStatus.values()[(seed - 1) % 3]);
        history.setFromDate(LocalDate.of(2026, 1, 1)); history.setToDate(LocalDate.of(2026, 1, 31));
        history.setModel(seed % 3 == 0 ? null : " raw model "); history.setTitle(" title " + seed);
        history.setSummary(new String[]{null, "", "\u2003", " raw summary "}[seed % 4]); history.setErrorMessage("raw-error");
        history.setCreatedAt(seed % 5 == 0 ? null : LocalDateTime.of(2026, 3, 8, 2, 30)); history.setResultJson(JSON[seed % JSON.length]); return history;
    }

    private record Outcome(String value, String failure, List<String> trace) {}
    private static final class Fixture {
        final List<String> trace = new ArrayList<>();
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        final AppUserService users = mock(AppUserService.class);
        final LedgerAiAnalysisHistoryRepository rows = mock(LedgerAiAnalysisHistoryRepository.class);
        final LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        final LedgerAiAnalysisMetrics metrics = mock(LedgerAiAnalysisMetrics.class);
        final ZoneId zone;
        final LedgerAnalysisHistoryLegacyOracle old;
        final ManageLedgerAnalysisHistoryService core;
        Fixture(int seed) {
            zone = ZoneId.of(new String[]{"UTC", "Asia/Seoul", "America/New_York"}[seed % 3]); properties.setModel("raw-model-" + seed % 3);
            when(metrics.providerLabel()).thenReturn("provider-" + seed % 3);
            when(users.getRequiredUser(7L)).thenAnswer(call -> { trace.add("owner:" + call.getArgument(0)); return null; });
            var codec = spy(new LedgerAiAnalysisJsonCodec(json));
            doAnswer(call -> { trace.add("decode:" + call.getArgument(0)); return call.callRealMethod(); }).when(codec).readResult(anyString());
            old = new LedgerAnalysisHistoryLegacyOracle(users, rows, properties, metrics, codec, new LedgerAiAnalysisTextSanitizer());
            core = new ManageLedgerAnalysisHistoryService(new LedgerAnalysisHistoryQueryAdapter(users, rows), new LedgerAnalysisHistoryJsonAdapter(codec), () -> NOW, () -> zone);
        }
        void check(Supplier<?> expected, Supplier<?> actual) throws Exception {
            Outcome oldValue = capture(expected), newValue = capture(actual);
            assertThat(newValue).isEqualTo(oldValue);
        }
        Outcome capture(Supplier<?> action) throws Exception {
            trace.clear(); String value = null, failure = null;
            try { value = json.writeValueAsString(action.get()); } catch (RuntimeException error) { failure = error.getClass().getName() + ":" + error.getMessage(); }
            return new Outcome(value, failure, List.copyOf(trace));
        }
    }
}
