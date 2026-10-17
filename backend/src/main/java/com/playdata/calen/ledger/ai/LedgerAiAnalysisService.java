package com.playdata.calen.ledger.ai;

import com.playdata.calen.ledger.adapter.out.analysis.LedgerAnalysisFormats;
import com.playdata.calen.ledger.adapter.out.analysis.LedgerAnalysisFormats.AnalysisDataset;
import com.playdata.calen.ledger.application.port.in.ExecuteLedgerAnalysisUseCase;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData.AnalysisPlan;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.DateRange;

import com.playdata.calen.ledger.adapter.out.jobs.LedgerAnalysisAdmissions;
import com.playdata.calen.ledger.adapter.support.LedgerAnalysisHistoryDrafts;
import com.playdata.calen.ledger.application.port.in.StartLedgerAnalysisUseCase;
import com.playdata.calen.ledger.application.service.StartLedgerAnalysisService;

import com.playdata.calen.ledger.adapter.support.LedgerAnalysisHistoryMapper;
import com.playdata.calen.ledger.application.port.in.ManageLedgerAnalysisHistoryUseCase;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.StoredHistory;

import com.playdata.calen.ledger.adapter.out.jobs.LedgerAiRecoverySources;
import com.playdata.calen.ledger.application.port.in.RecoverLedgerAiJobsUseCase;
import com.playdata.calen.ledger.application.service.RecoverLedgerAiJobsService;

import com.playdata.calen.ledger.adapter.support.LedgerAnalysisPreparationMapper;
import com.playdata.calen.ledger.domain.policy.LedgerAnalysisCalendarPolicy;
import com.playdata.calen.ledger.application.port.in.PrepareLedgerAnalysisDatasetUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerSpendingAnalysisPort;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Result;
import com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisBoundaryMapper;
import com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData.*;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisHistory;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisMode;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisStatus;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisHistoryDeleteResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisHistoryDetailResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisHistoryPageResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisHistorySummaryResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisRequest;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisStatusResponse;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.ledger.application.port.in.FinalizeLedgerAiJobUseCase;
import com.playdata.calen.ledger.domain.model.LedgerAiJobData.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerAiAnalysisService {

    private static final Duration DUPLICATE_SUPPRESSION_WINDOW = Duration.ofMinutes(5);

    private final AppUserService appUserService;
    private final PrepareLedgerAnalysisDatasetUseCase datasetPreparation;
    private final LedgerAiAnalysisHistoryRepository historyRepository;
    private final ManageLedgerAnalysisHistoryUseCase historyAccess;
    private final LedgerAiAnalysisProperties properties;
    private final LedgerAiAnalysisStatusService statusService;
    private final LedgerSpendingAnalysisPort remoteClient;
    private final LedgerAiAnalysisMetrics aiMetrics;
    private final LedgerAiAnalysisJsonCodec aiJsonCodec;
    private final LedgerAiAnalysisTextSanitizer aiText;
    private final FinalizeLedgerAiJobUseCase finalizer;
    private final LedgerAnalysisFormats analysisFormats;
    private final ExecuteLedgerAnalysisUseCase backgroundAnalysis;
    private final StartLedgerAnalysisUseCase analysisStart = new StartLedgerAnalysisService();

    @Autowired(required = false)
    @Qualifier("ledgerAiTaskExecutor")
    private TaskExecutor ledgerAiTaskExecutor;
    private final com.playdata.calen.common.cache.SingleFlight analysisAdmissions = new com.playdata.calen.common.cache.SingleFlight();
    private final java.util.concurrent.ConcurrentMap<Long, java.util.concurrent.FutureTask<Void>> analysisTasks = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Long, Integer> pendingAnalyses = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private com.playdata.calen.common.jobs.WorkLeaseService workLeases;

    public LedgerAiAnalysisStatusResponse getStatus() {
        return statusService.getStatus();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LedgerAiAnalysisHistoryDetailResponse startAnalyze(Long userId, LedgerAiAnalysisRequest request) {
        AnalysisPlan plan = resolvePlan(request);
        return analysisAdmissions.execute(analysisInFlightKey(userId, plan, normalizeClientRequestId(request.clientRequestId())),
                () -> startResolvedAnalysis(userId, plan));
    }

    private LedgerAiAnalysisHistoryDetailResponse startResolvedAnalysis(Long userId, AnalysisPlan plan) {
        return LedgerAnalysisHistoryMapper.response(analysisStart.start(LedgerAnalysisAdmissions.operation(
                userId, plan, appUserService, properties, aiMetrics, workLeases, historyRepository, historyAccess,
                id -> submitAnalysisTask(userId, id, plan),
                (id, failure) -> markAnalysisFailed(userId, id, failure)), DUPLICATE_SUPPRESSION_WINDOW));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LedgerAiAnalysisHistoryDetailResponse startRerun(Long userId, Long historyId) {
        LedgerAiAnalysisHistory history = historyRepository.findByIdAndOwnerId(historyId, userId)
                .orElseThrow(() -> new NotFoundException("AI analysis history was not found."));
        return startAnalyze(userId, new LedgerAiAnalysisRequest(
                history.getMode(),
                history.getPeriodType(),
                history.getComparisonPreset(),
                history.getToDate(),
                history.getFromDate(),
                history.getToDate(),
                history.getCompareFromDate(),
                history.getCompareToDate(),
                null,
                null
        ));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LedgerAiAnalysisResponse analyze(Long userId, LedgerAiAnalysisRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        if (!properties.isFeatureConfigured(LedgerAiFeature.LEDGER_ANALYSIS)) {
            throw new BadRequestException(properties.featureStatusMessage(LedgerAiFeature.LEDGER_ANALYSIS));
        }

        AnalysisPlan plan = resolvePlan(request);
        String clientRequestId = normalizeClientRequestId(request.clientRequestId());
        String inFlightKey = analysisInFlightKey(userId, plan, clientRequestId);
        return analysisAdmissions.execute("sync:" + inFlightKey, () -> analyzeResolvedPlan(owner, userId, plan));
    }

    private LedgerAiAnalysisResponse analyzeResolvedPlan(AppUser owner, Long userId, AnalysisPlan plan) {
        LedgerAiAnalysisResponse reusableResponse = aiText.hasText(plan.focusPrompt()) ? null : findReusableAnalysis(userId, plan);
        if (reusableResponse != null) return reusableResponse;

        AnalysisDataset dataset = buildDataset(userId, plan);
        LedgerAiN8nPayload payload = buildPayload(plan, dataset);
        LedgerAiAnalysisHistory pending = baseHistory(owner, plan);
        pending.setStatus(LedgerAiAnalysisStatus.PROCESSING);
        pending.setRequestPayloadJson(aiJsonCodec.write(payload));
        pending = historyRepository.save(pending);
        Long historyId = pending.getId();
        Timer.Sample aiRequestTimer = aiMetrics.startAiRequestTimer();
        boolean aiRequestRecorded = false;
        com.playdata.calen.common.jobs.WorkLeaseService.Lease lease = null;
        boolean inferenceOwned = workLeases == null;
        try {
            lease = workLeases == null ? null : workLeases.tryAcquire("analysis-job:" + historyId, Duration.ofMinutes(2));
            if (workLeases != null && lease == null)
                throw new com.playdata.calen.common.exception.ServiceUnavailableException("AI 분석 작업이 다른 서버에서 처리 중입니다. 잠시 후 다시 조회해 주세요.");
            inferenceOwned = true;
            Result remote = remoteClient.analyze(LedgerAnalysisBoundaryMapper.toRequest(payload));
            if (lease != null && !lease.isValid()) {
                aiMetrics.recordAiRequest(aiRequestTimer, "discarded");
                aiRequestRecorded = true;
                throw new com.playdata.calen.common.exception.ServiceUnavailableException("AI 분석 작업을 복구 중입니다. 잠시 후 다시 조회해 주세요.");
            }
            LedgerAiAnalysisResponse response = buildResponse(historyId, plan, dataset, remote);
            // Serialize the whole result before atomically exposing COMPLETED; no partial completed row.
            boolean completed = finalizer.complete(new Completion(new AnalysisJob(userId, historyId),
                    aiText.safeText(remote.summary()), aiJsonCodec.write(response), null, LocalDateTime.now()));
            aiMetrics.recordAiRequest(aiRequestTimer, completed ? "success" : "discarded");
            aiRequestRecorded = true;
            if (completed) return response;
            return readStoredHistoryResultOrFallback(historyRepository.findByIdAndOwnerId(historyId, userId)
                    .orElseThrow(() -> new NotFoundException("AI analysis history was not found.")));
        } catch (RuntimeException exception) {
            if (!aiRequestRecorded) aiMetrics.recordAiRequest(aiRequestTimer, "failure");
            if (inferenceOwned && (lease == null || lease.isValid())) markAnalysisFailed(userId, historyId, exception);
            throw exception;
        } finally {
            if (lease != null) lease.close();
        }
    }

    private void submitAnalysisTask(Long userId, Long historyId, AnalysisPlan plan) {
        submitAnalysisTask(userId, historyId, plan, false);
    }

    private void submitAnalysisTask(Long userId, Long historyId, AnalysisPlan plan, boolean recovering) {
        pendingAnalyses.compute(userId, (id, count) -> {
            if (count != null && count >= 5) throw new com.playdata.calen.common.exception.TooManyRequestsException("진행 중인 AI 분석이 많습니다. 잠시 후 다시 시도해 주세요.", 30);
            return count == null ? 1 : count + 1;
        });
        com.playdata.calen.common.jobs.WorkLeaseService.Lease lease;
        try {
            lease = workLeases == null ? null : workLeases.tryAcquire("analysis-job:" + historyId, Duration.ofMinutes(2));
        } catch (RuntimeException failure) {
            releasePendingAnalysis(userId);
            throw failure;
        }
        if (workLeases != null && lease == null) { releasePendingAnalysis(userId); return; }
        java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(() -> {
            if (lease == null || lease.isValid()) processAnalysisInBackground(userId, historyId, plan, lease);
        }, null) {
            @Override protected void done() {
                analysisTasks.remove(historyId, this);
                releasePendingAnalysis(userId);
                if (lease != null) lease.close();
                if (!isCancelled()) {
                    try { get(); }
                    catch (java.util.concurrent.ExecutionException failure) {
                        log.error("Ledger AI worker exited unexpectedly: historyId={}, failureType={}",
                                historyId, failure.getCause().getClass().getSimpleName());
                    } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
            }
        };
        if (analysisTasks.putIfAbsent(historyId, task) != null) { task.cancel(false); return; }
        try {
            if (ledgerAiTaskExecutor == null) throw new com.playdata.calen.common.exception.ServiceUnavailableException("AI 작업 실행기가 준비되지 않았습니다.");
            ledgerAiTaskExecutor.execute(task);
        } catch (RuntimeException exception) {
            task.cancel(false);
            if (!recovering) markAnalysisFailed(userId, historyId, exception);
            throw new com.playdata.calen.common.exception.TooManyRequestsException("AI 분석 대기열이 가득 찼습니다. 잠시 후 다시 시도해 주세요.", 30);
        }
    }

    private void releasePendingAnalysis(Long userId) {
        pendingAnalyses.computeIfPresent(userId, (id, count) -> count <= 1 ? null : count - 1);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void recoverAnalysisJobs() {
        jobRecovery.recoverAnalysis(LedgerAiRecoverySources.analysis(historyRepository, workLeases,
                () -> workLeases != null && ledgerAiTaskExecutor != null,
                () -> analysisRecoveryCursor, cursor -> analysisRecoveryCursor = cursor,
                analysisTasks::containsKey, owner -> pendingAnalyses.getOrDefault(owner, 0) >= 5,
                history -> markAnalysisFailed(history.getOwner().getId(), history.getId(),
                        new BadRequestException("이전 서버의 분석 요청은 복구할 수 없습니다. 다시 분석해 주세요.")),
                history -> {
                    AnalysisPlan plan = new AnalysisPlan(history.getMode(), history.getPeriodType(), history.getComparisonPreset(),
                            new DateRange(history.getFromDate(), history.getToDate()), history.getCompareFromDate() == null ? null
                            : new DateRange(history.getCompareFromDate(), history.getCompareToDate()), history.getFocusPrompt());
                    submitAnalysisTask(history.getOwner().getId(), history.getId(), plan, true);
                }));
    }

    private final RecoverLedgerAiJobsUseCase jobRecovery = new RecoverLedgerAiJobsService();
    private volatile long analysisRecoveryCursor;

    private void processAnalysisInBackground(Long userId, Long historyId, AnalysisPlan plan,
            com.playdata.calen.common.jobs.WorkLeaseService.Lease lease) {
        backgroundAnalysis.process(userId, historyId, plan, () -> lease == null || lease.isValid());
    }

    private void markAnalysisFailed(Long userId, Long historyId, RuntimeException exception) {
        finalizer.fail(new Failure(new AnalysisJob(userId, historyId), aiText.redactSensitiveText(exception.getMessage(), 500),
                "AI analysis failed.", LocalDateTime.now()));
    }

    public LedgerAiAnalysisHistoryPageResponse getHistories(Long userId, LedgerAiAnalysisMode mode,
            LedgerAiAnalysisPeriod periodType, LocalDate createdFrom, LocalDate createdTo, Boolean comparisonOnly, int page, int size) {
        return LedgerAnalysisHistoryMapper.response(historyAccess.search(userId,
                new LedgerAnalysisHistoryData.Filter(mode, periodType, createdFrom, createdTo, comparisonOnly, page, size)));
    }

    public LedgerAiAnalysisHistoryDetailResponse getHistory(Long userId, Long historyId) {
        return LedgerAnalysisHistoryMapper.response(historyAccess.detail(userId, historyId));
    }

    @Transactional
    public LedgerAiAnalysisHistoryDeleteResponse deleteHistory(Long userId, Long historyId) {
        return new LedgerAiAnalysisHistoryDeleteResponse(historyAccess.delete(userId, historyId));
    }

    @Transactional
    public LedgerAiAnalysisHistoryDeleteResponse deleteHistories(Long userId) {
        return new LedgerAiAnalysisHistoryDeleteResponse(historyAccess.deleteAll(userId));
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LedgerAiAnalysisResponse rerun(Long userId, Long historyId) {
        LedgerAiAnalysisHistory history = historyRepository.findByIdAndOwnerId(historyId, userId)
                .orElseThrow(() -> new NotFoundException("AI 분석 이력을 찾을 수 없습니다."));
        return analyze(userId, new LedgerAiAnalysisRequest(
                history.getMode(),
                history.getPeriodType(),
                history.getComparisonPreset(),
                history.getToDate(),
                history.getFromDate(),
                history.getToDate(),
                history.getCompareFromDate(),
                history.getCompareToDate(),
                null,
                null
        ));
    }

    public LedgerAiAnalysisHistoryDetailResponse getLatestMatching(Long userId, LedgerAiAnalysisRequest request) {
        appUserService.getRequiredUser(userId);
        AnalysisPlan plan = resolvePlan(request);
        if (aiText.hasText(plan.focusPrompt())) {
            return null;
        }
        return findLatestMatchingAnalysis(userId, plan, null)
                .map(history -> new LedgerAiAnalysisHistoryDetailResponse(toSummary(history), readStoredHistoryResultOrFallback(history)))
                .orElse(null);
    }

    private String normalizeClientRequestId(String clientRequestId) {
        return clientRequestId == null ? "" : clientRequestId.trim();
    }

    private String analysisInFlightKey(Long userId, AnalysisPlan plan, String clientRequestId) {
        DateRange comparison = plan.comparisonRange();
        return String.join("|",
                String.valueOf(userId),
                aiMetrics.providerLabel(),
                properties.getModel(),
                String.valueOf(plan.mode()),
                String.valueOf(plan.periodType()),
                String.valueOf(plan.primaryRange().from()),
                String.valueOf(plan.primaryRange().to()),
                comparison == null ? "" : String.valueOf(comparison.from()),
                comparison == null ? "" : String.valueOf(comparison.to()),
                plan.focusPrompt() == null ? "" : plan.focusPrompt(),
                clientRequestId == null ? "" : clientRequestId
        );
    }

    private LedgerAiAnalysisResponse readStoredHistoryResultOrFallback(LedgerAiAnalysisHistory history) {
        return readStoredHistoryResultOrFallback(LedgerAnalysisHistoryMapper.stored(history));
    }

    private LedgerAiAnalysisResponse readStoredHistoryResultOrFallback(StoredHistory history) {
        return LedgerAnalysisHistoryMapper.response(historyAccess.readStored(history));
    }

    private LedgerAiAnalysisResponse readReusableStoredHistoryResult(StoredHistory history) {
        return LedgerAnalysisHistoryMapper.response(historyAccess.readReusable(history));
    }

    private LedgerAiAnalysisResponse findReusableAnalysis(Long userId, AnalysisPlan plan) {
        LocalDateTime createdAfter = LocalDateTime.now().minus(DUPLICATE_SUPPRESSION_WINDOW);
        return findLatestMatchingAnalysis(userId, plan, createdAfter)
                .map(this::readReusableStoredHistoryResult)
                .orElse(null);
    }

    private Optional<StoredHistory> findLatestMatchingAnalysis(Long userId, AnalysisPlan plan, LocalDateTime createdAfter) {
        return historyAccess.completed(historyMatchKey(userId, plan, createdAfter));
    }

    private LedgerAnalysisHistoryData.MatchKey historyMatchKey(Long userId, AnalysisPlan plan, LocalDateTime createdAfter) {
        return new LedgerAnalysisHistoryData.MatchKey(userId, aiMetrics.providerLabel(), properties.getModel(),
                plan.mode(), plan.periodType(), plan.primaryRange().from(), plan.primaryRange().to(),
                plan.comparisonRange() == null ? null : plan.comparisonRange().from(),
                plan.comparisonRange() == null ? null : plan.comparisonRange().to(), createdAfter);
    }

    private AnalysisDataset buildDataset(Long userId, AnalysisPlan plan) {
        var compare = plan.comparisonRange();
        var source = datasetPreparation.prepare(userId, LedgerAnalysisPreparationMapper.plan(plan.mode(), plan.periodType(), plan.comparisonPreset(), plan.primaryRange().from(), plan.primaryRange().to(),
                compare == null ? null : LedgerAnalysisPreparationMapper.range(compare.from(), compare.to())));
        return new AnalysisDataset(LedgerAnalysisPreparationMapper.overview(source.overview()), LedgerAnalysisPreparationMapper.categories(source.categoryBreakdown()),
                LedgerAnalysisPreparationMapper.payments(source.paymentBreakdown()), LedgerAnalysisPreparationMapper.periods(source.periodComparison()),
                LedgerAnalysisPreparationMapper.expenses(source.expenseEntries()), LedgerAnalysisPreparationMapper.expenses(source.topExpenses()),
                LedgerAnalysisPreparationMapper.overview(source.comparisonOverview()), LedgerAnalysisPreparationMapper.categories(source.comparisonCategoryBreakdown()),
                LedgerAnalysisPreparationMapper.payments(source.comparisonPaymentBreakdown()), LedgerAnalysisPreparationMapper.expenses(source.comparisonExpenseEntries()));
    }

    private LedgerAiN8nPayload buildPayload(AnalysisPlan plan, AnalysisDataset dataset) {
        return analysisFormats.buildPayload(plan, dataset);
    }

    private LedgerAiAnalysisResponse buildResponse(Long historyId, AnalysisPlan plan, AnalysisDataset dataset, Result remote) {
        return analysisFormats.buildResponse(historyId, plan, dataset, remote);
    }
    private AnalysisPlan resolvePlan(LedgerAiAnalysisRequest request) {
        var resolved = LedgerAnalysisCalendarPolicy.resolvePlan(LedgerAnalysisPreparationMapper.draft(request), LocalDate::now);
        var compare = resolved.comparisonRange();
        return new AnalysisPlan(resolved.mode(), resolved.periodType(), resolved.comparisonPreset(),
                new DateRange(resolved.primaryRange().from(), resolved.primaryRange().to()), compare == null ? null : new DateRange(compare.from(), compare.to()),
                normalizeFocusPrompt(request.focusPrompt()));
    }

    private String normalizeFocusPrompt(String focusPrompt) {
        return aiText.redactSensitiveText(focusPrompt, 500);
    }

    private LedgerAiAnalysisHistory baseHistory(AppUser owner, AnalysisPlan plan) {
        return LedgerAnalysisHistoryDrafts.create(owner, plan, properties, aiMetrics);
    }

    private LedgerAiAnalysisHistorySummaryResponse toSummary(LedgerAiAnalysisHistory history) {
        return LedgerAnalysisHistoryMapper.response(LedgerAnalysisHistoryMapper.summary(history));
    }

    private LedgerAiAnalysisHistorySummaryResponse toSummary(StoredHistory history) {
        return LedgerAnalysisHistoryMapper.response(history.header());
    }

}


