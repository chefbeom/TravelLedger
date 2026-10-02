package com.playdata.calen.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.common.exception.*;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.domain.LedgerExcelAnalysisJob;
import com.playdata.calen.ledger.dto.LedgerExcelPreviewResponse;
import com.playdata.calen.ledger.repository.LedgerExcelAnalysisJobRepository;
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
    private final ConcurrentHashMap<String, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    private volatile RecoveryCursor recoveryCursor = new RecoveryCursor(null, "");

    public LedgerAiExcelJobService(LedgerAiExcelImportService importer, LedgerExcelAnalysisJobRepository jobs,
            WorkLeaseService leases, ObjectMapper mapper, @Qualifier("ledgerAiTaskExecutor") TaskExecutor executor) {
        this.importer = importer; this.jobs = jobs; this.leases = leases; this.mapper = mapper; this.executor = executor;
    }

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
            if (!recovering) fail(id);
            throw new TooManyRequestsException("Excel 분석 대기열이 가득 찼습니다. 잠시 후 다시 시도해 주세요.", 30);
        }
    }

    private void process(String id, WorkLeaseService.Lease lease) {
        LedgerExcelAnalysisJob job = jobs.findById(id).orElse(null);
        if (job == null || !"PROCESSING".equals(job.getStatus()) || !lease.isValid()) return;
        try {
            LedgerExcelPreviewResponse result = importer.analyzePreparedInput(job.getInputJson());
            if (!lease.isValid()) return;
            job.setResultJson(mapper.writeValueAsString(result)); job.setInputJson(null); job.setStatus("COMPLETED");
            jobs.save(job);
        } catch (Exception failure) { if (lease.isValid()) fail(id); }
    }

    private void fail(String id) {
        jobs.findById(id).ifPresent(job -> {
            job.setStatus("FAILED"); job.setInputJson(null);
            job.setErrorMessage("Excel AI 분석에 실패했습니다. 설정을 확인하거나 다시 시도해 주세요."); jobs.save(job);
        });
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void recover() {
        LocalDateTime before = LocalDateTime.now().minusMinutes(1);
        RecoveryCursor cursor = recoveryCursor;
        LocalDateTime afterTime = cursor.createdAt();
        String afterId = cursor.id();
        for (int batch = 0; batch < 5; batch++) {
            var candidates = jobs.findRecoveryPage("PROCESSING", before, afterTime, afterId, PageRequest.of(0, 100));
            for (var job : candidates) {
                afterTime = job.getCreatedAt();
                afterId = job.getId();
                recoveryCursor = new RecoveryCursor(afterTime, afterId);
                if (tasks.containsKey(job.getId())) continue;
                try { submit(job, true); } catch (TooManyRequestsException capacity) { return; }
            }
            if (candidates.size() < 100) { recoveryCursor = new RecoveryCursor(null, ""); return; }
        }
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
    private record RecoveryCursor(LocalDateTime createdAt, String id) { }
}
