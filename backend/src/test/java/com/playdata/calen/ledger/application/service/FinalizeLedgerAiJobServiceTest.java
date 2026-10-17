package com.playdata.calen.ledger.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.ledger.application.port.out.LedgerAiJobStatePort;
import com.playdata.calen.ledger.domain.model.LedgerAiJobData.*;
import com.playdata.calen.ledger.domain.policy.LedgerAiJobLifecyclePolicy;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class FinalizeLedgerAiJobServiceTest {
    private final LedgerAiJobStatePort states = mock(LedgerAiJobStatePort.class);
    private final FinalizeLedgerAiJobService service = new FinalizeLedgerAiJobService(states);
    private final LocalDateTime now = LocalDateTime.of(2026,10,16,0,0);
    static Stream<Job> jobs() { return Stream.of(new AnalysisJob(7L,1L),new ImageJob(7L,2L),new ExcelJob(7L,"excel",3L)); }
    @ParameterizedTest @MethodSource("jobs")
    void completionReportsSuccessOrDiscardWithoutMergingOrRetrying(Job job) {
        var command = new Completion(job,"summary","{}",new ImageText("AUTO","text"),now);
        when(states.completeProcessing(command)).thenReturn(1,0);
        assertThat(service.complete(command)).isTrue(); assertThat(service.complete(command)).isFalse();
        verify(states,times(2)).completeProcessing(command); verifyNoMoreInteractions(states);
    }
    @ParameterizedTest @MethodSource("jobs")
    void failureCannotTurnALostRaceIntoAnUnconditionalWrite(Job job) {
        var command = new Failure(job,"error","failed",now);
        when(states.failProcessing(command)).thenReturn(1,0);
        assertThat(service.fail(command)).isTrue(); assertThat(service.fail(command)).isFalse();
        verify(states,times(2)).failProcessing(command); verifyNoMoreInteractions(states);
    }
    @Test void onlyTheExistingImageJobContractExposesCancellation() {
        var command = new Cancellation(new ImageJob(7L,2L),"cancelled",now);
        when(states.cancelProcessing(command)).thenReturn(1,0);
        assertThat(service.cancel(command)).isTrue(); assertThat(service.cancel(command)).isFalse();
    }
    @Test void excelCannotFinalizeWithoutItsPreInferenceVersion() {
        var job = new ExcelJob(7L,"excel",null);
        assertThatThrownBy(() -> service.complete(new Completion(job,null,"{}",null,now))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.fail(new Failure(job,"error",null,now))).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(states);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","\n"})
    void completedStateRequiresASerializedResultBeforeAnyWrite(String result) {
        assertThatThrownBy(() -> service.complete(new Completion(new AnalysisJob(7L,1L),"summary",result,null,now))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(states);
    }
    @ParameterizedTest @ValueSource(ints = {-1,2})
    void unexpectedAffectedRowCountsAreRejected(int rows) {
        var command = new Cancellation(new ImageJob(7L,2L),"cancelled",now);
        when(states.cancelProcessing(command)).thenReturn(rows);
        assertThatThrownBy(() -> service.cancel(command)).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"COMPLETED","FAILED","CANCELLED","UNKNOWN"})
    void terminalMissingAndUnknownStatesAreNotRunnable(String state) { assertThat(LedgerAiJobLifecyclePolicy.isProcessing(state)).isFalse(); }
    @Test void existingProcessingWireValueRemainsRunnable() { assertThat(LedgerAiJobLifecyclePolicy.isProcessing("PROCESSING")).isTrue(); }
}
