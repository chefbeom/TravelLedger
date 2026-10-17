package com.playdata.calen.ledger.fixture;

import com.playdata.calen.common.exception.*;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.repository.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.springframework.data.domain.PageRequest;

/** Frozen pre-extraction loops. Hooks stand in for the unchanged submission/finalization methods. */
public final class LedgerAiRecoveryLegacyOracle {
    private LedgerAiRecoveryLegacyOracle() {}
    public record DateRange(LocalDate from, LocalDate to) {
    }
    public record AnalysisPlan(
            LedgerAiAnalysisMode mode,
            LedgerAiAnalysisPeriod periodType,
            LedgerAiComparisonPreset comparisonPreset,
            DateRange primaryRange,
            DateRange comparisonRange,
            String focusPrompt
    ) {
    }
    public record AnalysisSubmission(Long owner, Long id, AnalysisPlan plan, boolean recovering) {}
    public record Failure(Long owner, Long id, RuntimeException error) {}

    public static final class Analysis {
        public LedgerAiAnalysisHistoryRepository historyRepository;
        public WorkLeaseService workLeases;
        public Object ledgerAiTaskExecutor;
        public Map<Long, Object> analysisTasks = new HashMap<>();
        public Map<Long, Integer> pendingAnalyses = new HashMap<>();
        public long analysisRecoveryCursor;
        public Consumer<Failure> reject;
        public Consumer<AnalysisSubmission> submit;
        private void markAnalysisFailed(Long owner, Long id, RuntimeException error) { reject.accept(new Failure(owner, id, error)); }
        private void submitAnalysisTask(Long owner, Long id, AnalysisPlan plan, boolean recovering) { submit.accept(new AnalysisSubmission(owner, id, plan, recovering)); }
        @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000, initialDelay = 60000)
        public void recoverAnalysisJobs() {
            if (workLeases == null || ledgerAiTaskExecutor == null) return;
            long cursor = analysisRecoveryCursor;
            for (int batch = 0; batch < 5; batch++) {
            var histories = historyRepository.findAllByStatusAndCreatedAtBeforeAndIdGreaterThanOrderByIdAsc(
                    LedgerAiAnalysisStatus.PROCESSING, LocalDateTime.now().minusMinutes(1), cursor, PageRequest.of(0, 100));
            if (histories.isEmpty()) { analysisRecoveryCursor = 0; return; }
            cursor = histories.get(histories.size() - 1).getId();
            for (LedgerAiAnalysisHistory history : histories) {
                analysisRecoveryCursor = history.getId();
                if (analysisTasks.containsKey(history.getId())) continue;
                if (pendingAnalyses.getOrDefault(history.getOwner().getId(), 0) >= 5) continue;
                if (history.getFocusPrompt() == null) {
                    try (var lease = workLeases.tryAcquire("analysis-job:" + history.getId(), Duration.ofMinutes(2))) {
                        if (lease != null) markAnalysisFailed(history.getOwner().getId(), history.getId(),
                                new BadRequestException("이전 서버의 분석 요청은 복구할 수 없습니다. 다시 분석해 주세요."));
                    }
                    continue;
                }
                AnalysisPlan plan = new AnalysisPlan(history.getMode(), history.getPeriodType(), history.getComparisonPreset(),
                        new DateRange(history.getFromDate(), history.getToDate()), history.getCompareFromDate() == null ? null
                        : new DateRange(history.getCompareFromDate(), history.getCompareToDate()), history.getFocusPrompt());
                try { submitAnalysisTask(history.getOwner().getId(), history.getId(), plan, true); }
                catch (com.playdata.calen.common.exception.TooManyRequestsException capacity) { return; }
            }
            if (histories.size() < 100) { analysisRecoveryCursor = 0; return; }
            }
        }
    }

    public static final class Images {
        public LedgerImageAnalysisRequestRepository imageAnalysisRequestRepository;
        public WorkLeaseService workLeases;
        public Object ledgerOcrTaskExecutor;
        public Map<Long, Object> imageAnalysisTasks = new HashMap<>();
        public long imageRecoveryCursor;
        public Consumer<Failure> reject;
        public LongConsumer reserve;
        public Consumer<LedgerImageAnalysisRequest> submit;
        private boolean hasStoredImage(LedgerImageAnalysisRequest history) { return history != null && history.getImageObjectKey() != null && !history.getImageObjectKey().isBlank(); }
        private void failImageAnalysisRequest(LedgerImageAnalysisRequest history, RuntimeException error) { reject.accept(new Failure(history.getOwner().getId(), history.getId(), error)); }
        private void reserveImageAnalysis(Long owner) { reserve.accept(owner); }
        private void submitImageAnalysisTask(Long owner, LedgerImageAnalysisRequest history, Object temporaryImage, String documentType, String prompt, boolean recovering) {
            if (temporaryImage != null || !recovering) throw new AssertionError("Recovery must use persisted input");
            submit.accept(history);
        }
        @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000, initialDelay = 60000)
        public void recoverImageJobs() {
            if (ledgerOcrTaskExecutor == null || workLeases == null) return;
            long cursor = imageRecoveryCursor;
            for (int batch = 0; batch < 5; batch++) {
            var histories = imageAnalysisRequestRepository.findAllByStatusAndCreatedAtBeforeAndIdGreaterThanOrderByIdAsc(
                    LedgerImageAnalysisStatus.PROCESSING, LocalDateTime.now().minusMinutes(1), cursor, PageRequest.of(0, 100));
            if (histories.isEmpty()) { imageRecoveryCursor = 0; return; }
            cursor = histories.get(histories.size() - 1).getId();
            for (LedgerImageAnalysisRequest history : histories) {
                imageRecoveryCursor = history.getId();
                if (imageAnalysisTasks.containsKey(history.getId())) continue;
                if (!hasStoredImage(history) || history.getEffectivePrompt() == null) {
                    // Legacy jobs and temporary-only uploads cannot be reconstructed after restart.
                    try (var lease = workLeases.tryAcquire("image-job:" + history.getId(), java.time.Duration.ofMinutes(30))) {
                        if (lease != null) failImageAnalysisRequest(history,
                                new BadRequestException("서버 재시작으로 입력을 복구할 수 없습니다. 이미지를 다시 분석해 주세요."));
                    }
                    continue;
                }
                boolean reserved = false;
                try {
                    reserveImageAnalysis(history.getOwner().getId());
                    reserved = true;
                    submitImageAnalysisTask(history.getOwner().getId(), history, null, history.getDocumentType(), history.getEffectivePrompt(), true);
                } catch (TooManyRequestsException capacity) {
                    // Admission has no task cleanup when reservation itself failed.
                    if (!reserved) continue;
                    return;
                }
            }
            if (histories.size() < 100) { imageRecoveryCursor = 0; return; }
            }
        }
    }

    public record RecoveryCursor(LocalDateTime createdAt, String id) {}
    public static final class Workbooks {
        public LedgerExcelAnalysisJobRepository jobs;
        public Map<String, Object> tasks = new HashMap<>();
        public RecoveryCursor recoveryCursor = new RecoveryCursor(null, "");
        public Consumer<LedgerExcelAnalysisJob> submit;
        private void submit(LedgerExcelAnalysisJob job, boolean recovering) {
            if (!recovering) throw new AssertionError("Recovery submission required");
            submit.accept(job);
        }
        @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000, initialDelay = 60000)
        public void recover() {
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
    }
}
