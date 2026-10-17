package com.playdata.calen.ledger.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.TooManyRequestsException;
import com.playdata.calen.ledger.application.port.out.LedgerAnalysisAdmissionPort;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.Detail;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StartLedgerAnalysisServiceTest {
    final StartLedgerAnalysisService service = new StartLedgerAnalysisService();
    final LedgerAnalysisAdmissionPort operation = mock(LedgerAnalysisAdmissionPort.class);
    final Duration window = Duration.ofMinutes(5);
    final LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0);

    @Test void ownerFailureStopsBeforeConfigurationOrLeaseAccess() {
        doThrow(new IllegalStateException("owner missing")).when(operation).requireActiveOwner();
        assertThatThrownBy(() -> service.start(operation, window)).hasMessage("owner missing");
        verify(operation).requireActiveOwner(); verifyNoMoreInteractions(operation);
    }
    @Test void configurationFailureStopsBeforeDistributedAdmission() {
        doThrow(new IllegalStateException("disabled")).when(operation).requireConfigured();
        assertThatThrownBy(() -> service.start(operation, window)).hasMessage("disabled");
        var order = inOrder(operation); order.verify(operation).requireActiveOwner(); order.verify(operation).requireConfigured();
        verifyNoMoreInteractions(operation);
    }
    @Test void processingReusePrecedesCompletedReuseAndCreationWithoutALeaseService() {
        var detail = new Detail(null, null);
        when(operation.now()).thenReturn(now); when(operation.processing(now.minus(window))).thenReturn(Optional.of(detail));
        assertThat(service.start(operation, window)).isSameAs(detail);
        verify(operation, never()).reusable(any()); verify(operation, never()).createProcessing(); verify(operation, never()).tryAdmission();
    }
    @Test void completedReuseUsesTheSameCutoffAndReleasesTheAdmissionLease() {
        var lease = mock(LedgerAnalysisAdmissionPort.AdmissionLease.class); var detail = new Detail(null, null);
        when(operation.usesDistributedAdmission()).thenReturn(true); when(operation.tryAdmission()).thenReturn(lease);
        when(operation.now()).thenReturn(now); when(operation.reusable(now.minus(window))).thenReturn(Optional.of(detail));
        assertThat(service.start(operation, window)).isSameAs(detail);
        var order = inOrder(operation, lease); order.verify(operation).processing(now.minus(window)); order.verify(operation).reusable(now.minus(window)); order.verify(lease).close();
        verify(operation, never()).createProcessing();
    }
    @Test void busyAdmissionCanReturnOnlyProcessingNotCompletedHistory() {
        when(operation.usesDistributedAdmission()).thenReturn(true); when(operation.now()).thenReturn(now);
        var detail = new Detail(null, null); when(operation.processing(now.minus(window))).thenReturn(Optional.of(detail));
        assertThat(service.start(operation, window)).isSameAs(detail);
        verify(operation, never()).reusable(any()); verify(operation, never()).createProcessing();
    }
    @Test void customFocusAtBusyAdmissionNeverQueriesReuseAndKeepsOneSecondRetry() {
        when(operation.usesDistributedAdmission()).thenReturn(true); when(operation.focusPrompt()).thenReturn("custom");
        var failure = catchThrowable(() -> service.start(operation, window));
        assertThat(failure).isInstanceOf(TooManyRequestsException.class).hasMessage("AI 분석 요청을 접수 중입니다. 잠시 후 다시 시도해 주세요.");
        assertThat(((TooManyRequestsException) failure).getRetryAfterSeconds()).isEqualTo(1);
        verify(operation, never()).now(); verify(operation, never()).processing(any()); verify(operation, never()).reusable(any());
    }
    @Test void newPendingSummaryIsReadOnlyAfterSubmissionAndTheLeaseIsClosedLast() {
        var lease = mock(LedgerAnalysisAdmissionPort.AdmissionLease.class); var detail = new Detail(null, null);
        when(operation.usesDistributedAdmission()).thenReturn(true); when(operation.tryAdmission()).thenReturn(lease);
        when(operation.focusPrompt()).thenReturn("custom"); when(operation.pendingDetail()).thenReturn(detail);
        assertThat(service.start(operation, window)).isSameAs(detail);
        var order = inOrder(operation, lease); order.verify(operation).createProcessing(); order.verify(operation).submit(); order.verify(operation).pendingDetail(); order.verify(lease).close();
    }
    @Test void submissionFailureFinalizesThenRethrowsTheSameErrorAndClosesTheLease() {
        var lease = mock(LedgerAnalysisAdmissionPort.AdmissionLease.class); var failure = new IllegalStateException("queue failed");
        when(operation.usesDistributedAdmission()).thenReturn(true); when(operation.tryAdmission()).thenReturn(lease); when(operation.focusPrompt()).thenReturn("custom");
        doThrow(failure).when(operation).submit();
        assertThat(catchThrowable(() -> service.start(operation, window))).isSameAs(failure);
        var order = inOrder(operation, lease); order.verify(operation).submit(); order.verify(operation).submissionFailed(failure); order.verify(lease).close();
        verify(operation, never()).pendingDetail();
    }
    @Test void draftPersistenceFailureDoesNotTryToFailANonexistentJob() {
        when(operation.focusPrompt()).thenReturn("custom"); doThrow(new IllegalStateException("save failed")).when(operation).createProcessing();
        assertThatThrownBy(() -> service.start(operation, window)).hasMessage("save failed");
        verify(operation, never()).submit(); verify(operation, never()).submissionFailed(any());
    }
    @Test void finalizationFailureReplacesSubmissionFailureButStillClosesTheLease() {
        var lease = mock(LedgerAnalysisAdmissionPort.AdmissionLease.class);
        when(operation.usesDistributedAdmission()).thenReturn(true); when(operation.tryAdmission()).thenReturn(lease); when(operation.focusPrompt()).thenReturn("custom");
        doThrow(new IllegalStateException("queue failed")).when(operation).submit(); doThrow(new IllegalArgumentException("finalization failed")).when(operation).submissionFailed(any());
        assertThatThrownBy(() -> service.start(operation, window)).hasMessage("finalization failed"); verify(lease).close();
    }
}
