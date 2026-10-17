package com.playdata.calen.ledger.ai;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.*;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.dto.*;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;

/** Frozen historical methods; no migrated history mapper/use case/query adapter is used. */
@RequiredArgsConstructor
public final class LedgerAnalysisHistoryLegacyOracle {
    private static final int MAX_HISTORY_PAGE_SIZE = 50;
    private static final Duration DUPLICATE_SUPPRESSION_WINDOW = Duration.ofMinutes(5);
    private final AppUserService appUserService;
    private final LedgerAiAnalysisHistoryRepository historyRepository;
    private final LedgerAiAnalysisProperties properties;
    private final LedgerAiAnalysisMetrics aiMetrics;
    private final LedgerAiAnalysisJsonCodec aiJsonCodec;
    private final LedgerAiAnalysisTextSanitizer aiText;
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
    public LedgerAiAnalysisHistoryPageResponse getHistories(
            Long userId,
            LedgerAiAnalysisMode mode,
            LedgerAiAnalysisPeriod periodType,
            LocalDate createdFrom,
            LocalDate createdTo,
            Boolean comparisonOnly,
            int page,
            int size
    ) {
        appUserService.getRequiredUser(userId);
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new BadRequestException("조회 시작일은 종료일보다 이후일 수 없습니다.");
        }

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, MAX_HISTORY_PAGE_SIZE));
        PageRequest pageRequest = PageRequest.of(safePage, safeSize);
        LocalDateTime createdFromDateTime = createdFrom == null ? null : createdFrom.atStartOfDay();
        LocalDateTime createdToExclusive = createdTo == null ? null : createdTo.plusDays(1).atStartOfDay();
        Page<LedgerAiAnalysisHistory> resultPage = historyRepository.searchHistories(
                userId,
                mode,
                periodType,
                createdFromDateTime,
                createdToExclusive,
                comparisonOnly,
                pageRequest
        );

        return new LedgerAiAnalysisHistoryPageResponse(
                resultPage.getContent().stream().map(this::toSummary).toList(),
                resultPage.getNumber(),
                resultPage.getSize(),
                resultPage.getTotalElements(),
                resultPage.getTotalPages()
        );
    }

    public LedgerAiAnalysisHistoryDetailResponse getHistory(Long userId, Long historyId) {
        appUserService.getRequiredUser(userId);
        LedgerAiAnalysisHistory history = historyRepository.findByIdAndOwnerId(historyId, userId)
                .orElseThrow(() -> new NotFoundException("AI 분석 이력을 찾을 수 없습니다."));
        LedgerAiAnalysisResponse result = readStoredHistoryResultOrFallback(history);
        return new LedgerAiAnalysisHistoryDetailResponse(toSummary(history), result);
    }

    public LedgerAiAnalysisHistoryDeleteResponse deleteHistory(Long userId, Long historyId) {
        appUserService.getRequiredUser(userId);
        int deletedCount = historyRepository.deleteByIdAndOwnerId(historyId, userId);
        if (deletedCount == 0) {
            throw new NotFoundException("AI analysis history was not found.");
        }
        return new LedgerAiAnalysisHistoryDeleteResponse(deletedCount);
    }

    public LedgerAiAnalysisHistoryDeleteResponse deleteHistories(Long userId) {
        appUserService.getRequiredUser(userId);
        return new LedgerAiAnalysisHistoryDeleteResponse(historyRepository.deleteAllByOwnerId(userId));
    }

    public LedgerAiAnalysisResponse readStoredHistoryResultOrFallback(LedgerAiAnalysisHistory history) {
        if (history == null) {
            return null;
        }
        if (history.getStatus() == LedgerAiAnalysisStatus.PROCESSING) {
            return null;
        }
        if (history.getStatus() != LedgerAiAnalysisStatus.COMPLETED || !aiText.hasText(history.getResultJson())) {
            return buildUnreadableHistoryResult(history, "저장된 AI 분석 결과가 없습니다. 재분석을 실행해 주세요.");
        }
        try {
            LedgerAiAnalysisResponse result = aiJsonCodec.readResult(history.getResultJson());
            return result == null
                    ? buildUnreadableHistoryResult(history, "저장된 AI 분석 결과가 비어 있습니다. 재분석을 실행해 주세요.")
                    : result;
        } catch (RuntimeException exception) {
            return buildUnreadableHistoryResult(history, "저장된 AI 분석 결과를 읽지 못했습니다. 재분석을 실행해 주세요.");
        }
    }

    public LedgerAiAnalysisResponse readReusableStoredHistoryResult(LedgerAiAnalysisHistory history) {
        if (history == null || history.getStatus() != LedgerAiAnalysisStatus.COMPLETED || !aiText.hasText(history.getResultJson())) {
            return null;
        }
        try {
            return aiJsonCodec.readResult(history.getResultJson());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public LedgerAiAnalysisResponse buildUnreadableHistoryResult(LedgerAiAnalysisHistory history, String message) {
        String summary = firstNonBlank(history.getSummary(), message);
        LedgerAiAnalysisReportResponse report = new LedgerAiAnalysisReportResponse(
                summary,
                "이 저장 이력의 원본 분석 결과가 비어 있거나 현재 응답 형식과 맞지 않습니다. 재분석 버튼으로 새 결과를 생성해 주세요.",
                "저장된 상세 지표를 읽을 수 없어 금액 분석은 표시하지 않습니다.",
                List.of(),
                List.of(),
                List.of(message),
                "",
                List.of(),
                List.of(),
                List.of("이 이력을 재분석해 새 결과를 저장하세요."),
                List.of()
        );
        return new LedgerAiAnalysisResponse(
                history.getId(),
                history.getMode(),
                history.getPeriodType(),
                history.getComparisonPreset(),
                history.getFromDate(),
                history.getToDate(),
                history.getCompareFromDate(),
                history.getCompareToDate(),
                history.getModel(),
                history.getCreatedAt() == null ? Instant.now() : history.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L,
                BigDecimal.ZERO,
                List.of(),
                List.of(),
                List.of(),
                report,
                summary,
                List.of("저장된 분석 이력은 확인됐지만 상세 결과를 읽을 수 없습니다."),
                List.of(message),
                List.of("재분석을 실행해 현재 형식의 결과를 다시 저장하세요."),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                ""
        );
    }

    public LedgerAiAnalysisResponse findReusableAnalysis(Long userId, AnalysisPlan plan) {
        LocalDateTime createdAfter = LocalDateTime.now().minus(DUPLICATE_SUPPRESSION_WINDOW);
        return findLatestMatchingAnalysis(userId, plan, createdAfter)
                .map(this::readReusableStoredHistoryResult)
                .orElse(null);
    }

    public java.util.Optional<LedgerAiAnalysisHistory> findLatestMatchingAnalysis(Long userId, AnalysisPlan plan, LocalDateTime createdAfter) {
        return historyRepository.findLatestMatchingCompletedAnalysis(
                userId,
                LedgerAiAnalysisStatus.COMPLETED,
                aiMetrics.providerLabel(),
                properties.getModel(),
                plan.mode(),
                plan.periodType(),
                plan.primaryRange().from(),
                plan.primaryRange().to(),
                plan.comparisonRange() == null ? null : plan.comparisonRange().from(),
                plan.comparisonRange() == null ? null : plan.comparisonRange().to(),
                createdAfter,
                Limit.of(1)
        );
    }

    public Optional<LedgerAiAnalysisHistory> findLatestMatchingProcessingAnalysis(
            Long userId,
            AnalysisPlan plan,
            LocalDateTime createdAfter
    ) {
        return historyRepository.findLatestMatchingProcessingAnalysis(
                userId,
                LedgerAiAnalysisStatus.PROCESSING,
                aiMetrics.providerLabel(),
                properties.getModel(),
                plan.mode(),
                plan.periodType(),
                plan.primaryRange().from(),
                plan.primaryRange().to(),
                plan.comparisonRange() == null ? null : plan.comparisonRange().from(),
                plan.comparisonRange() == null ? null : plan.comparisonRange().to(),
                createdAfter,
                Limit.of(1)
        );
    }

    public LedgerAiAnalysisHistorySummaryResponse toSummary(LedgerAiAnalysisHistory history) {
        return new LedgerAiAnalysisHistorySummaryResponse(
                history.getId(),
                history.getMode(),
                history.getPeriodType(),
                history.getComparisonPreset(),
                history.getStatus(),
                history.getFromDate(),
                history.getToDate(),
                history.getCompareFromDate(),
                history.getCompareToDate(),
                history.getModel(),
                history.getTitle(),
                history.getSummary(),
                history.getErrorMessage(),
                history.getCreatedAt()
        );
    }

    public String firstNonBlank(String primary, String fallback) {
        return aiText.hasText(primary) ? primary : aiText.safeText(fallback);
    }
}
