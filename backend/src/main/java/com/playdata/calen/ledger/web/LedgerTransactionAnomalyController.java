package com.playdata.calen.ledger.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.dto.LedgerTransactionAnomalyResponse;
import com.playdata.calen.ledger.service.LedgerTransactionAnomalyService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/entries/anomalies")
@RequiredArgsConstructor
public class LedgerTransactionAnomalyController {

    private final LedgerTransactionAnomalyService ledgerTransactionAnomalyService;

    @GetMapping
    public LedgerTransactionAnomalyResponse findAnomalies(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Integer limit
    ) {
        return ledgerTransactionAnomalyService.findAnomalies(currentUser.userId(), from, to, limit);
    }
}