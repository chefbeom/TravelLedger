package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.domain.LedgerExcelAnalysisJob;
import com.playdata.calen.ledger.dto.LedgerExcelPreviewResponse;
import com.playdata.calen.ledger.repository.LedgerExcelAnalysisJobRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.web.MockMultipartFile;

class LedgerAiExcelJobServiceTest {
    @Test
    void boundedRecoveryContinuesOnNextRunInsteadOfRescanningFirstFiveBatches() {
        var repository = mock(LedgerExcelAnalysisJobRepository.class);
        var leases = mock(WorkLeaseService.class);
        var timestamp = java.time.LocalDateTime.now().minusMinutes(2);
        var pending = java.util.stream.IntStream.range(0, 501).mapToObj(index -> {
            var job = new LedgerExcelAnalysisJob(); job.setId(String.format("%03d", index)); job.setCreatedAt(timestamp);
            return job;
        }).toList();
        when(repository.findRecoveryPage(eq("PROCESSING"), any(), any(), anyString(), any())).thenAnswer(call -> {
            String cursor = call.getArgument(3);
            int start = cursor.isEmpty() ? 0 : Integer.parseInt(cursor) + 1;
            return pending.subList(start, Math.min(start + 100, pending.size()));
        });
        var service = new LedgerAiExcelJobService(mock(LedgerAiExcelImportService.class), repository, leases,
                new ObjectMapper(), mock(TaskExecutor.class));

        service.recover();
        verify(leases, never()).tryAcquire(eq("excel-job:500"), any());
        service.recover();
        verify(leases).tryAcquire(eq("excel-job:500"), any());
    }

    @Test
    void recoveryAdvancesPastJobsOwnedByOtherInstances() {
        var repository = mock(LedgerExcelAnalysisJobRepository.class);
        var leases = mock(WorkLeaseService.class);
        var timestamp = java.time.LocalDateTime.now().minusMinutes(2);
        var firstPage = java.util.stream.IntStream.range(0, 100).mapToObj(index -> {
            var job = new LedgerExcelAnalysisJob();
            job.setId(String.format("%03d", index)); job.setCreatedAt(timestamp);
            return job;
        }).toList();
        var next = new LedgerExcelAnalysisJob(); next.setId("100"); next.setCreatedAt(timestamp.plusSeconds(1));
        when(repository.findRecoveryPage(eq("PROCESSING"), any(), isNull(), eq(""), any())).thenReturn(firstPage);
        when(repository.findRecoveryPage(eq("PROCESSING"), any(), eq(timestamp), eq("099"), any())).thenReturn(List.of(next));
        var service = new LedgerAiExcelJobService(mock(LedgerAiExcelImportService.class), repository, leases,
                new ObjectMapper(), mock(TaskExecutor.class));

        service.recover();

        verify(leases).tryAcquire(eq("excel-job:100"), any());
        verify(repository).findRecoveryPage(eq("PROCESSING"), any(), eq(timestamp), eq("099"), any());
    }

    @Test
    void queuedWorkerLoadsPersistedInputAndOnlyOwnerCanReadResult() {
        var importer = mock(LedgerAiExcelImportService.class);
        var repository = mock(LedgerExcelAnalysisJobRepository.class);
        var leases = mock(WorkLeaseService.class);
        var lease = mock(WorkLeaseService.Lease.class);
        when(lease.isValid()).thenReturn(true);
        when(leases.tryAcquire(anyString(), any())).thenReturn(lease);
        AtomicReference<LedgerExcelAnalysisJob> saved = new AtomicReference<>();
        when(repository.saveAndFlush(any())).thenAnswer(call -> { saved.set(call.getArgument(0)); return saved.get(); });
        when(repository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(saved.get()));
        when(repository.findByIdAndOwnerId(anyString(), eq(7L))).thenAnswer(call -> Optional.ofNullable(saved.get()));
        when(importer.prepareInput(eq(7L), any())).thenReturn("persisted workbook DTO");
        var expected = new LedgerExcelPreviewResponse("file.xlsx", "sheet", 1, 0, 0, 0, List.of(), List.of());
        when(importer.analyzePreparedInput("persisted workbook DTO")).thenReturn(expected);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        TaskExecutor executor = queued::set;
        var service = new LedgerAiExcelJobService(importer, repository, leases, new ObjectMapper(), executor);
        var response = service.start(7L, new MockMultipartFile("file", "file.xlsx", "application/octet-stream", new byte[]{1}));
        assertThat(response.status()).isEqualTo("PROCESSING");
        verify(importer, never()).analyzePreparedInput(anyString());
        queued.get().run();
        assertThat(service.get(7L, response.jobId()).result()).isEqualTo(expected);
        assertThat(saved.get().getInputJson()).isNull();
        assertThatThrownBy(() -> service.get(8L, response.jobId())).isInstanceOf(NotFoundException.class);
    }
}
