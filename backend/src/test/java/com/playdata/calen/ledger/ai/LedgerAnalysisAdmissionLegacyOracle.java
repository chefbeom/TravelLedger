package com.playdata.calen.ledger.ai;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.*;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.adapter.support.LedgerAnalysisHistoryMapper;
import com.playdata.calen.ledger.application.port.in.ManageLedgerAnalysisHistoryUseCase;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.*;
import com.playdata.calen.ledger.dto.*;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import java.time.*;
import java.util.*;
import java.util.function.*;
import lombok.RequiredArgsConstructor;

/** Frozen async admission/draft/title bodies. History access is unchanged from the previous slice. */
@RequiredArgsConstructor
public final class LedgerAnalysisAdmissionLegacyOracle {
    private static final Duration DUPLICATE_SUPPRESSION_WINDOW = Duration.ofMinutes(5);
    private final AppUserService appUserService;
    private final LedgerAiAnalysisProperties properties;
    private final LedgerAiAnalysisMetrics aiMetrics;
    private final WorkLeaseService workLeases;
    private final LedgerAiAnalysisHistoryRepository historyRepository;
    private final ManageLedgerAnalysisHistoryUseCase historyAccess;
    private final LongConsumer submit;
    private final BiConsumer<Long, RuntimeException> failed;
    private final LedgerAiAnalysisTextSanitizer aiText = new LedgerAiAnalysisTextSanitizer();
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
    public LedgerAiAnalysisHistoryDetailResponse startResolvedAnalysis(Long userId, AnalysisPlan plan) {
        AppUser owner = appUserService.getRequiredUser(userId);
        if (!properties.isFeatureConfigured(LedgerAiFeature.LEDGER_ANALYSIS)) {
            throw new BadRequestException(properties.featureStatusMessage(LedgerAiFeature.LEDGER_ANALYSIS));
        }

        if (workLeases == null) return startAdmittedAnalysis(owner, userId, plan);
        String admissionKey = "analysis-admission:" + org.springframework.util.DigestUtils.md5DigestAsHex(
                (userId + ":" + plan).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var admission = workLeases.tryAcquire(admissionKey, Duration.ofMinutes(2))) {
            if (admission == null) {
                if (!aiText.hasText(plan.focusPrompt())) {
                    var processing = findLatestMatchingProcessingAnalysis(userId, plan,
                            LocalDateTime.now().minus(DUPLICATE_SUPPRESSION_WINDOW));
                    if (processing.isPresent()) return new LedgerAiAnalysisHistoryDetailResponse(toSummary(processing.get()), null);
                }
                throw new com.playdata.calen.common.exception.TooManyRequestsException("AI 분석 요청을 접수 중입니다. 잠시 후 다시 시도해 주세요.", 1);
            }
            return startAdmittedAnalysis(owner, userId, plan);
        }
    }

    public LedgerAiAnalysisHistoryDetailResponse startAdmittedAnalysis(AppUser owner, Long userId, AnalysisPlan plan) {
        if (!aiText.hasText(plan.focusPrompt())) {
            LocalDateTime duplicateSince = LocalDateTime.now().minus(DUPLICATE_SUPPRESSION_WINDOW);
            Optional<StoredHistory> processingHistory = findLatestMatchingProcessingAnalysis(
                    userId,
                    plan,
                    duplicateSince
            );
            if (processingHistory.isPresent()) {
                return new LedgerAiAnalysisHistoryDetailResponse(toSummary(processingHistory.get()), null);
            }
            Optional<StoredHistory> reusableHistory = findLatestMatchingAnalysis(
                    userId,
                    plan,
                    duplicateSince
            );
            if (reusableHistory.isPresent()) {
                LedgerAiAnalysisResponse reusableResponse = readReusableStoredHistoryResult(reusableHistory.get());
                if (reusableResponse != null) {
                    return new LedgerAiAnalysisHistoryDetailResponse(toSummary(reusableHistory.get()), reusableResponse);
                }
            }
        }

        LedgerAiAnalysisHistory history = baseHistory(owner, plan);
        history.setStatus(LedgerAiAnalysisStatus.PROCESSING);
        history.setSummary("AI analysis is processing.");
        history = historyRepository.save(history);
        try { submitAnalysisTask(userId, history.getId(), plan); }
        catch (RuntimeException failure) { markAnalysisFailed(userId, history.getId(), failure); throw failure; }
        return new LedgerAiAnalysisHistoryDetailResponse(toSummary(history), null);
    }

    public LedgerAiAnalysisHistory baseHistory(AppUser owner, AnalysisPlan plan) {
        LedgerAiAnalysisHistory history = new LedgerAiAnalysisHistory();
        history.setOwner(owner);
        history.setFocusPrompt(plan.focusPrompt() == null ? "" : plan.focusPrompt());
        history.setMode(plan.mode());
        history.setPeriodType(plan.periodType());
        history.setComparisonPreset(plan.comparisonPreset());
        history.setFromDate(plan.primaryRange().from());
        history.setToDate(plan.primaryRange().to());
        if (plan.comparisonRange() != null) {
            history.setCompareFromDate(plan.comparisonRange().from());
            history.setCompareToDate(plan.comparisonRange().to());
        }
        history.setModel(properties.getModel());
        history.setProvider(aiMetrics.providerLabel());
        history.setTitle(buildTitle(plan));
        return history;
    }

    public String buildTitle(AnalysisPlan plan) {
        String range = plan.primaryRange().from() + " ~ " + plan.primaryRange().to();
        if (plan.mode() == LedgerAiAnalysisMode.COMPARISON && plan.comparisonRange() != null) {
            if (plan.comparisonPreset() == LedgerAiComparisonPreset.WEEKLY_DIGEST) {
                return "AI weekly spending digest - " + range + " vs " + plan.comparisonRange().from() + " ~ " + plan.comparisonRange().to();
            }
            return "AI comparison analysis - " + range + " vs " + plan.comparisonRange().from() + " ~ " + plan.comparisonRange().to();
        }
        return "AI period analysis - " + range;
    }
    private void submitAnalysisTask(Long owner, Long id, AnalysisPlan plan) { submit.accept(id); }
    private void markAnalysisFailed(Long owner, Long id, RuntimeException error) { failed.accept(id, error); }
    private Optional<StoredHistory> findLatestMatchingProcessingAnalysis(Long owner, AnalysisPlan plan, LocalDateTime since) { return historyAccess.processing(key(owner, plan, since)); }
    private Optional<StoredHistory> findLatestMatchingAnalysis(Long owner, AnalysisPlan plan, LocalDateTime since) { return historyAccess.completed(key(owner, plan, since)); }
    private LedgerAiAnalysisResponse readReusableStoredHistoryResult(StoredHistory history) { return LedgerAnalysisHistoryMapper.response(historyAccess.readReusable(history)); }
    private LedgerAiAnalysisHistorySummaryResponse toSummary(StoredHistory history) { return LedgerAnalysisHistoryMapper.response(history.header()); }
    private LedgerAiAnalysisHistorySummaryResponse toSummary(LedgerAiAnalysisHistory history) { return LedgerAnalysisHistoryMapper.response(LedgerAnalysisHistoryMapper.summary(history)); }
    private com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.MatchKey key(Long owner, AnalysisPlan plan, LocalDateTime since) {
        return new com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.MatchKey(owner, aiMetrics.providerLabel(), properties.getModel(), plan.mode(), plan.periodType(),
                plan.primaryRange().from(), plan.primaryRange().to(), plan.comparisonRange() == null ? null : plan.comparisonRange().from(),
                plan.comparisonRange() == null ? null : plan.comparisonRange().to(), since);
    }
}
