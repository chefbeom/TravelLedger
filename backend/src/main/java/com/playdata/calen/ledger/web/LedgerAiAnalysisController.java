package com.playdata.calen.ledger.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisService;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisMode;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisHistoryDeleteResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisHistoryDetailResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisHistoryPageResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisRequest;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisResponse;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisStatusResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/statistics/ai-analysis")
@RequiredArgsConstructor
public class LedgerAiAnalysisController {

    private final LedgerAiAnalysisService ledgerAiAnalysisService;

    @GetMapping("/status")
    public LedgerAiAnalysisStatusResponse status() {
        return ledgerAiAnalysisService.getStatus();
    }

    @GetMapping("/history")
    public LedgerAiAnalysisHistoryPageResponse histories(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(required = false) LedgerAiAnalysisMode mode,
            @RequestParam(required = false) LedgerAiAnalysisPeriod periodType,
            @RequestParam(required = false) LocalDate createdFrom,
            @RequestParam(required = false) LocalDate createdTo,
            @RequestParam(required = false) Boolean comparisonOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ledgerAiAnalysisService.getHistories(
                currentUser.userId(),
                mode,
                periodType,
                createdFrom,
                createdTo,
                comparisonOnly,
                page,
                size
        );
    }

    @GetMapping("/history/{historyId}")
    public LedgerAiAnalysisHistoryDetailResponse history(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long historyId
    ) {
        return ledgerAiAnalysisService.getHistory(currentUser.userId(), historyId);
    }

    @DeleteMapping("/history/{historyId}")
    public LedgerAiAnalysisHistoryDeleteResponse deleteHistory(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long historyId
    ) {
        return ledgerAiAnalysisService.deleteHistory(currentUser.userId(), historyId);
    }

    @DeleteMapping("/history")
    public LedgerAiAnalysisHistoryDeleteResponse deleteHistories(
            @AuthenticationPrincipal AppUserPrincipal currentUser
    ) {
        return ledgerAiAnalysisService.deleteHistories(currentUser.userId());
    }

    @PostMapping("/history/{historyId}/rerun")
    public LedgerAiAnalysisResponse rerun(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long historyId
    ) {
        return ledgerAiAnalysisService.rerun(currentUser.userId(), historyId);
    }

    @PostMapping("/latest")
    public LedgerAiAnalysisHistoryDetailResponse latest(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody LedgerAiAnalysisRequest request
    ) {
        return ledgerAiAnalysisService.getLatestMatching(currentUser.userId(), request);
    }

    @PostMapping
    public LedgerAiAnalysisResponse analyze(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody LedgerAiAnalysisRequest request
    ) {
        return ledgerAiAnalysisService.analyze(currentUser.userId(), request);
    }
}