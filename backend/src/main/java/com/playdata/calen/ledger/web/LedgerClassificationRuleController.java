package com.playdata.calen.ledger.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.dto.LedgerClassificationPreviewRequest;
import com.playdata.calen.ledger.dto.LedgerClassificationPreviewResponse;
import com.playdata.calen.ledger.dto.LedgerClassificationRuleRequest;
import com.playdata.calen.ledger.dto.LedgerClassificationRuleResponse;
import com.playdata.calen.ledger.service.LedgerClassificationRuleService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger/classification-rules")
@RequiredArgsConstructor
public class LedgerClassificationRuleController {

    private final LedgerClassificationRuleService ledgerClassificationRuleService;

    @GetMapping
    public List<LedgerClassificationRuleResponse> getRules(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(defaultValue = "false") boolean includeInactive
    ) {
        return ledgerClassificationRuleService.getRules(currentUser.userId(), includeInactive);
    }

    @PostMapping
    public LedgerClassificationRuleResponse createRule(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody LedgerClassificationRuleRequest request
    ) {
        return ledgerClassificationRuleService.createRule(currentUser.userId(), request);
    }

    @PutMapping("/{ruleId}")
    public LedgerClassificationRuleResponse updateRule(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long ruleId,
            @Valid @RequestBody LedgerClassificationRuleRequest request
    ) {
        return ledgerClassificationRuleService.updateRule(currentUser.userId(), ruleId, request);
    }

    @DeleteMapping("/{ruleId}")
    public LedgerClassificationRuleResponse deactivateRule(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long ruleId
    ) {
        return ledgerClassificationRuleService.deactivateRule(currentUser.userId(), ruleId);
    }

    @PostMapping("/preview")
    public LedgerClassificationPreviewResponse preview(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody(required = false) LedgerClassificationPreviewRequest request
    ) {
        return ledgerClassificationRuleService.preview(currentUser.userId(), request);
    }
}