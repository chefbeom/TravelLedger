package com.playdata.calen.ledger.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.dto.LedgerImageAnalysisHistoryResponse;
import com.playdata.calen.ledger.dto.LedgerOcrAnalyzeResponse;
import com.playdata.calen.ledger.ocr.LedgerOcrService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(path = {"/api/ledger/ocr", "/api/ledger/image-analysis"})
@RequiredArgsConstructor
public class LedgerOcrController {

    private final LedgerOcrService ledgerOcrService;

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LedgerOcrAnalyzeResponse analyzeReceipt(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "documentType", defaultValue = "AUTO") String documentType,
            @RequestParam(name = "clientRequestId", required = false) String clientRequestId,
            @RequestParam(name = "prompt", required = false) String prompt,
            @RequestParam(name = "useExistingEntryStyle", defaultValue = "false") boolean useExistingEntryStyle
    ) {
        return ledgerOcrService.analyze(currentUser.userId(), file, documentType, clientRequestId, prompt, useExistingEntryStyle);
    }

    @GetMapping("/history")
    public Page<LedgerImageAnalysisHistoryResponse> listHistories(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            Pageable pageable
    ) {
        return ledgerOcrService.listHistories(currentUser.userId(), pageable);
    }

    @GetMapping("/history/{historyId}")
    public LedgerImageAnalysisHistoryResponse getHistory(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long historyId
    ) {
        return ledgerOcrService.getHistory(currentUser.userId(), historyId);
    }

    @PostMapping("/history/client/{clientRequestId}/cancel")
    public LedgerImageAnalysisHistoryResponse cancelHistoryByClientRequestId(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable String clientRequestId
    ) {
        return ledgerOcrService.cancelHistoryByClientRequestId(currentUser.userId(), clientRequestId);
    }

    @PostMapping("/history/{historyId}/cancel")
    public LedgerImageAnalysisHistoryResponse cancelHistory(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long historyId
    ) {
        return ledgerOcrService.cancelHistory(currentUser.userId(), historyId);
    }
}
