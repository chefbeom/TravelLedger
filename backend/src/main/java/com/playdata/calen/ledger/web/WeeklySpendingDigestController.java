package com.playdata.calen.ledger.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.ai.WeeklySpendingDigestService;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisHistoryDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/statistics/ai-analysis/weekly-digest")
@RequiredArgsConstructor
public class WeeklySpendingDigestController {

    private final WeeklySpendingDigestService weeklySpendingDigestService;

    @GetMapping("/latest")
    public LedgerAiAnalysisHistoryDetailResponse latest(
            @AuthenticationPrincipal AppUserPrincipal currentUser
    ) {
        return weeklySpendingDigestService.getLatest(currentUser.userId());
    }

    @PostMapping("/run")
    public LedgerAiAnalysisHistoryDetailResponse runNow(
            @AuthenticationPrincipal AppUserPrincipal currentUser
    ) {
        return weeklySpendingDigestService.requestNow(currentUser.userId());
    }
}
