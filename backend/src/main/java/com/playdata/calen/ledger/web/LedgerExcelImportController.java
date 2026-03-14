package com.playdata.calen.ledger.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.dto.LedgerExcelImportRequest;
import com.playdata.calen.ledger.dto.LedgerExcelImportResultResponse;
import com.playdata.calen.ledger.dto.LedgerExcelPreviewResponse;
import com.playdata.calen.ledger.service.LedgerExcelImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/entries/imports/excel")
@RequiredArgsConstructor
public class LedgerExcelImportController {

    private final LedgerExcelImportService ledgerExcelImportService;

    @PostMapping("/preview")
    public LedgerExcelPreviewResponse preview(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam("file") MultipartFile file
    ) {
        return ledgerExcelImportService.preview(currentUser.userId(), file);
    }

    @PostMapping("/commit")
    public LedgerExcelImportResultResponse commit(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody LedgerExcelImportRequest request
    ) {
        return ledgerExcelImportService.importRows(currentUser.userId(), request);
    }
}
