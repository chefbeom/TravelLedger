package com.playdata.calen.ledger.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.dto.RecurringLedgerOccurrenceResponse;
import com.playdata.calen.ledger.dto.RecurringLedgerRuleRequest;
import com.playdata.calen.ledger.dto.RecurringLedgerRuleResponse;
import com.playdata.calen.ledger.service.RecurringLedgerService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recurring-ledger")
@RequiredArgsConstructor
public class RecurringLedgerController {

    private final RecurringLedgerService recurringLedgerService;

    @GetMapping("/rules")
    public List<RecurringLedgerRuleResponse> listRules(
            @AuthenticationPrincipal AppUserPrincipal currentUser
    ) {
        return recurringLedgerService.listRules(currentUser.userId());
    }

    @PostMapping("/rules")
    public RecurringLedgerRuleResponse createRule(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody RecurringLedgerRuleRequest request
    ) {
        return recurringLedgerService.createRule(currentUser.userId(), request);
    }

    @PutMapping("/rules/{ruleId}")
    public RecurringLedgerRuleResponse updateRule(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long ruleId,
            @Valid @RequestBody RecurringLedgerRuleRequest request
    ) {
        return recurringLedgerService.updateRule(currentUser.userId(), ruleId, request);
    }

    @DeleteMapping("/rules/{ruleId}")
    public void deactivateRule(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long ruleId
    ) {
        recurringLedgerService.deactivateRule(currentUser.userId(), ruleId);
    }

    @GetMapping("/occurrences/pending")
    public List<RecurringLedgerOccurrenceResponse> listPendingOccurrences(
            @AuthenticationPrincipal AppUserPrincipal currentUser
    ) {
        return recurringLedgerService.listPendingOccurrences(currentUser.userId());
    }

    @PostMapping("/occurrences/{occurrenceId}/approve")
    public RecurringLedgerOccurrenceResponse approveOccurrence(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long occurrenceId
    ) {
        return recurringLedgerService.approveOccurrence(currentUser.userId(), occurrenceId);
    }

    @PostMapping("/occurrences/{occurrenceId}/skip")
    public RecurringLedgerOccurrenceResponse skipOccurrence(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long occurrenceId
    ) {
        return recurringLedgerService.skipOccurrence(currentUser.userId(), occurrenceId);
    }
}
