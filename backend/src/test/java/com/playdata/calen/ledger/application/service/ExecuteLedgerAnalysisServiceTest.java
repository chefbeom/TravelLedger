package com.playdata.calen.ledger.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.ledger.application.port.in.*;
import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAiJobData.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData.AnalysisPlan;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Request;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Result;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.AnalysisResult;
import java.time.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;

class ExecuteLedgerAnalysisServiceTest {
    final PrepareLedgerAnalysisDatasetUseCase datasets = mock(PrepareLedgerAnalysisDatasetUseCase.class);
    final LedgerAnalysisWorkStore jobs = mock(LedgerAnalysisWorkStore.class);
    final LedgerAnalysisWorkFormats formats = mock(LedgerAnalysisWorkFormats.class);
    final LedgerSpendingAnalysisPort provider = mock(LedgerSpendingAnalysisPort.class);
    final FinalizeLedgerAiJobUseCase finalizer = mock(FinalizeLedgerAiJobUseCase.class);
    final LedgerAnalysisWorkMonitoring monitoring = mock(LedgerAnalysisWorkMonitoring.class);
    final BooleanSupplier ownership = mock(BooleanSupplier.class);
    final Dataset dataset = mock(Dataset.class);
    final LedgerAnalysisWorkFormats.Prepared prepared = mock(LedgerAnalysisWorkFormats.Prepared.class);
    final Request request = mock(Request.class);
    final Result remote = mock(Result.class);
    final AnalysisResult response = mock(AnalysisResult.class);
    final LedgerAnalysisWorkMonitoring.Sample sample = mock(LedgerAnalysisWorkMonitoring.Sample.class);
    final LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0);
    final AnalysisPlan plan = new AnalysisPlan(LedgerAiAnalysisMode.PERIOD, LedgerAiAnalysisPeriod.MONTH, null,
            new DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)), null, "");
    final ExecuteLedgerAnalysisService service = new ExecuteLedgerAnalysisService(datasets, jobs, formats, provider, finalizer, monitoring, () -> now);

    @BeforeEach void normalFlow() {
        when(jobs.status(7L, 9L)).thenReturn("PROCESSING"); when(datasets.prepare(eq(7L), any())).thenReturn(dataset);
        when(formats.prepare(plan, dataset)).thenReturn(prepared); when(prepared.serialize()).thenReturn("payload");
        when(jobs.savePayload(7L, 9L, "payload")).thenReturn(1); when(monitoring.start()).thenReturn(sample);
        when(prepared.request()).thenReturn(request); when(provider.analyze(request)).thenReturn(remote);
        when(ownership.getAsBoolean()).thenReturn(true); when(formats.response(9L, plan, dataset, remote)).thenReturn(response);
        when(remote.summary()).thenReturn(" raw summary "); when(formats.serializeResult(response)).thenReturn("result");
        when(finalizer.complete(any())).thenReturn(true); when(formats.failureText(any())).thenReturn("safe failure");
    }
    void run() { service.process(7L, 9L, plan, ownership); }

    @Test void missingOrTerminalRowsStopBeforePreparationAndLeaseChecks() {
        when(jobs.status(7L, 9L)).thenReturn(null, "COMPLETED", "FAILED"); run(); run(); run();
        verifyNoInteractions(datasets, formats, provider, monitoring, finalizer, ownership);
    }
    @Test void preparedSerializationAndConditionalInputSavePrecedeMetricsAndProviderMapping() {
        when(jobs.savePayload(7L, 9L, "payload")).thenReturn(0); run();
        var order = inOrder(datasets, formats, prepared, jobs);
        order.verify(datasets).prepare(eq(7L), any()); order.verify(formats).prepare(plan, dataset); order.verify(prepared).serialize(); order.verify(jobs).savePayload(7L, 9L, "payload");
        verify(prepared, never()).request(); verifyNoInteractions(monitoring, provider, finalizer, ownership);
    }
    @Test void successfulCompletionSerializesTheWholeResponseBeforeTheTerminalUpdateAndRecordsSuccess() {
        run();
        var order = inOrder(monitoring, prepared, provider, ownership, formats, finalizer);
        order.verify(monitoring).start(); order.verify(prepared).request(); order.verify(provider).analyze(request); order.verify(ownership).getAsBoolean();
        order.verify(formats).response(9L, plan, dataset, remote); order.verify(formats).serializeResult(response);
        order.verify(finalizer).complete(new Completion(new AnalysisJob(7L, 9L), " raw summary ", "result", null, now));
        order.verify(monitoring).record(sample, "success"); verify(finalizer, never()).fail(any());
    }
    @Test void lostCompletionRaceIsDiscardedNotFailed() {
        when(finalizer.complete(any())).thenReturn(false); run();
        verify(monitoring).record(sample, "discarded"); verify(finalizer, never()).fail(any()); verify(monitoring, never()).warn(any(), any());
    }
    @Test void leaseLossAfterInferenceDoesNotBuildSerializeFinalizeOrRecordAnOutcome() {
        when(ownership.getAsBoolean()).thenReturn(false); run();
        verify(formats, never()).response(any(), any(), any(), any()); verify(formats, never()).serializeResult(any());
        verifyNoInteractions(finalizer); verify(monitoring, never()).record(any(), any()); verify(monitoring, never()).warn(any(), any());
    }
    @Test void inferenceFailureAfterLeaseLossDoesNotFailAnotherWorkersJobOrRecordFailure() {
        when(provider.analyze(request)).thenThrow(new IllegalStateException("provider failed")); when(ownership.getAsBoolean()).thenReturn(false); run();
        verifyNoInteractions(finalizer); verify(monitoring, never()).record(any(), any()); verify(monitoring, never()).warn(any(), any());
    }
    @Test void preTimerFailureStillUsesSafeConditionalFailureAndNoFailureMetric() {
        var error = new IllegalStateException("private payload detail"); when(prepared.serialize()).thenThrow(error); run();
        verify(finalizer).fail(new Failure(new AnalysisJob(7L, 9L), "safe failure", "AI analysis failed.", now));
        verify(monitoring).warn(9L, error); verify(monitoring, never()).start(); verify(monitoring, never()).record(any(), any());
    }
    @Test void mappingFailureAfterTheTimerStartsIsRecordedBeforeFailureFinalization() {
        var error = new IllegalStateException("request mapping failed"); when(prepared.request()).thenThrow(error); run();
        var order = inOrder(monitoring, finalizer); order.verify(monitoring).start(); order.verify(monitoring).record(sample, "failure");
        order.verify(finalizer).fail(any()); order.verify(monitoring).warn(9L, error); verifyNoInteractions(provider);
    }
    @Test void responseSerializationFailureCannotPublishCompleted() {
        when(formats.serializeResult(response)).thenThrow(new IllegalStateException("serialize failed")); run();
        verify(finalizer, never()).complete(any()); verify(finalizer).fail(any()); verify(monitoring).record(sample, "failure");
    }
    @Test void disabledTimerStillReceivesSuccessButDoesNotRecordAnErrorSample() {
        when(monitoring.start()).thenReturn(null); run(); verify(monitoring).record(null, "success");
        clearInvocations(monitoring, finalizer); when(provider.analyze(request)).thenThrow(new IllegalStateException("failed")); run();
        verify(monitoring, never()).record(any(), any()); verify(finalizer).fail(any());
    }
    @Test void initialLookupFailureRemainsOutsideTheExecutionCatch() {
        when(jobs.status(7L, 9L)).thenThrow(new IllegalStateException("lookup failed"));
        assertThatThrownBy(this::run).hasMessage("lookup failed"); verifyNoInteractions(finalizer, monitoring, ownership);
    }
    @Test void failedSuccessMetricRetainsTheOriginalSecondFailureMetricAndConditionalFailureAttempt() {
        doThrow(new IllegalStateException("metric failed")).when(monitoring).record(sample, "success"); run();
        var order = inOrder(finalizer, monitoring); order.verify(finalizer).complete(any()); order.verify(monitoring).record(sample, "success");
        order.verify(monitoring).record(sample, "failure"); order.verify(finalizer).fail(any()); order.verify(monitoring).warn(eq(9L), any());
    }
    @Test void failureFinalizationErrorEscapesWithoutTheBoundedWarning() {
        when(provider.analyze(request)).thenThrow(new IllegalStateException("provider failed"));
        doThrow(new IllegalArgumentException("state unavailable")).when(finalizer).fail(any());
        assertThatThrownBy(this::run).hasMessage("state unavailable"); verify(monitoring, never()).warn(any(), any());
    }
    @Test void nullRemoteSummaryIsTheExistingEmptyStringAtCompletion() {
        when(remote.summary()).thenReturn(null); run();
        verify(finalizer).complete(new Completion(new AnalysisJob(7L, 9L), "", "result", null, now));
    }
}
