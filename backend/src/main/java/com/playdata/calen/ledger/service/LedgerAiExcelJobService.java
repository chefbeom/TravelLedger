package com.playdata.calen.ledger.service;

import com.playdata.calen.ledger.adapter.out.jobs.LedgerAiRecoverySources;
import com.playdata.calen.ledger.application.port.in.RecoverLedgerAiJobsUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerAiRecoveryPort.WorkbookCursor;
import com.playdata.calen.ledger.application.service.RecoverLedgerAiJobsService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.common.exception.*;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.domain.LedgerExcelAnalysisJob;
import com.playdata.calen.ledger.dto.LedgerExcelPreviewResponse;
import com.playdata.calen.ledger.repository.LedgerExcelAnalysisJobRepository;
import com.playdata.calen.ledger.application.port.in.FinalizeLedgerAiJobUseCase;
import com.playdata.calen.ledger.domain.model.LedgerAiJobData.*;
import com.playdata.calen.ledger.domain.policy.LedgerAiJobLifecyclePolicy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LedgerAiExcelJobService {
    private final LedgerAiExcelImportService importer;
    private final LedgerExcelAnalysisJobRepository jobs;
    private final WorkLeaseService leases;
    private final ObjectMapper mapper;
    private final TaskExecutor executor;
    private final FinalizeLedgerAiJobUseCase finalizer;
    private final ConcurrentHashMap<String, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    private final RecoverLedgerAiJobsUseCase jobRecovery = new RecoverLedgerAiJobsService();
    private volatile WorkbookCursor recoveryCursor = new WorkbookCursor(null, "");

    public LedgerAiExcelJobService(LedgerAiExcelImportService importer, LedgerExcelAnalysisJobRepository jobs,
            WorkLeaseService leases, ObjectMapper mapper, @Qualifier("ledgerAiTaskExecutor") TaskExecutor executor, FinalizeLedgerAiJobUseCase finalizer) {
        this.importer = importer; this.jobs = jobs; this.leases = leases; this.mapper = mapper; this.executor = executor;
        this.finalizer = finalizer;
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public JobResponse start(Long userId, MultipartFile file) {
        try (var admission = leases.tryAcquire("excel-admission:" + userId, Duration.ofMinutes(2))) {
            if (admission == null || jobs.countByOwnerIdAndStatus(userId, "PROCESSING") >= 5)
                throw new TooManyRequestsException("진행 중인 Excel 분석이 많습니다. 잠시 후 다시 시도해 주세요.", 30);
            String input = importer.prepareInput(userId, file);
            LedgerExcelAnalysisJob job = new LedgerExcelAnalysisJob();
            job.setId(UUID.randomUUID().toString()); job.setOwnerId(userId); job.setInputJson(input);
            jobs.saveAndFlush(job);
            submit(job, false);
            return response(job);
        }
    }

    public JobResponse get(Long userId, String id) {
        return response(jobs.findByIdAndOwnerId(id, userId).orElseThrow(() -> new NotFoundException("Excel 분석 작업을 찾을 수 없습니다.")));
    }

    private void submit(LedgerExcelAnalysisJob job, boolean recovering) {
        var lease = leases.tryAcquire("excel-job:" + job.getId(), Duration.ofMinutes(2));
        if (lease == null) return;
        String id = job.getId();
        FutureTask<Void> task = new FutureTask<>(() -> process(id, lease), null) {
            @Override protected void done() { tasks.remove(id, this); lease.close(); }
        };
        if (tasks.putIfAbsent(id, task) != null) { task.cancel(false); return; }
        try { executor.execute(task); }
        catch (RuntimeException failure) {
            task.cancel(false);
            if (!recovering) fail(job);
            throw new TooManyRequestsException("Excel 분석 대기열이 가득 찼습니다. 잠시 후 다시 시도해 주세요.", 30);
        }
    }

    private void process(String id, WorkLeaseService.Lease lease) {
        LedgerExcelAnalysisJob job = jobs.findById(id).orElse(null);
        if (job == null || !LedgerAiJobLifecyclePolicy.isProcessing(job.getStatus()) || !lease.isValid()) return;
        try {
            LedgerExcelPreviewResponse result = importer.analyzePreparedInput(job.getInputJson());
            if (!lease.isValid()) return;
            finalizer.complete(new Completion(ref(job), null, mapper.writeValueAsString(result), null, LocalDateTime.now()));
        } catch (Exception failure) { if (lease.isValid()) fail(job); }
    }

    private void fail(LedgerExcelAnalysisJob job) {
        finalizer.fail(new Failure(ref(job), "Excel AI 분석에 실패했습니다. 설정을 확인하거나 다시 시도해 주세요.", null, LocalDateTime.now()));
    }

    private ExcelJob ref(LedgerExcelAnalysisJob job) { return new ExcelJob(job.getOwnerId(), job.getId(), job.getVersion()); }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void recover() {
        jobRecovery.recoverWorkbooks(LedgerAiRecoverySources.workbooks(jobs,
                () -> recoveryCursor, cursor -> recoveryCursor = cursor,
                tasks::containsKey, job -> submit(job, true)));
    }

    private JobResponse response(LedgerExcelAnalysisJob job) {
        LedgerExcelPreviewResponse result = null;
        if (job.getResultJson() != null) {
            try { result = mapper.readValue(job.getResultJson(), LedgerExcelPreviewResponse.class); }
            catch (Exception failure) { throw new ServiceUnavailableException("Excel 분석 결과를 읽을 수 없습니다."); }
        }
        return new JobResponse(job.getId(), job.getStatus(), result, job.getErrorMessage());
    }

    public record JobResponse(String jobId, String status, LedgerExcelPreviewResponse result, String errorMessage) { }
}
