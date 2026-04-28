package com.playdata.calen.ledger.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.dto.LedgerOcrAnalyzeResponse;
import com.playdata.calen.ledger.ocr.LedgerOcrService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ledger/ocr")
@RequiredArgsConstructor
public class LedgerOcrController {

    private final LedgerOcrService ledgerOcrService;

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LedgerOcrAnalyzeResponse analyzeReceipt(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "documentType", defaultValue = "AUTO") String documentType
    ) {
        return ledgerOcrService.analyze(currentUser.userId(), file, documentType);
    }
}
