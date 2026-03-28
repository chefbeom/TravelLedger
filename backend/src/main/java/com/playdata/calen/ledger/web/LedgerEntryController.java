package com.playdata.calen.ledger.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.ledger.service.LedgerEntryService;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/entries")
@RequiredArgsConstructor
public class LedgerEntryController {

    private final LedgerEntryService ledgerEntryService;

    @GetMapping
    public List<LedgerEntryResponse> getEntries(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ledgerEntryService.getEntries(currentUser.userId(), from, to);
    }

    @GetMapping("/export/csv")
    public ResponseEntity<ByteArrayResource> exportEntriesCsv(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        LedgerEntryService.LedgerCsvExport export = ledgerEntryService.exportEntriesCsv(currentUser.userId(), from, to);
        String encodedFileName = URLEncoder.encode(export.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                .contentType(MediaType.parseMediaType("text/csv; charset=" + export.charset()))
                .contentLength(export.content().length)
                .body(new ByteArrayResource(export.content()));
    }

    @PostMapping
    public LedgerEntryResponse createEntry(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody LedgerEntryRequest request
    ) {
        return ledgerEntryService.create(currentUser.userId(), request);
    }

    @PutMapping("/{id}")
    public LedgerEntryResponse updateEntry(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long id,
            @Valid @RequestBody LedgerEntryRequest request
    ) {
        return ledgerEntryService.update(currentUser.userId(), id, request);
    }

    @DeleteMapping("/{id}")
    public void deleteEntry(@AuthenticationPrincipal AppUserPrincipal currentUser, @PathVariable Long id) {
        ledgerEntryService.delete(currentUser.userId(), id);
    }
}
