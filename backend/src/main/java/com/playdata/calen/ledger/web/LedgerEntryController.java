package com.playdata.calen.ledger.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.security.SecondaryPinSessionSupport;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.dto.LedgerCsvExportRequest;
import com.playdata.calen.ledger.dto.LedgerEntryDateRangeResponse;
import com.playdata.calen.ledger.dto.LedgerEntryPageResponse;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.ledger.dto.LedgerEntrySearchPageResponse;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.service.LedgerEntryService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
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

    @GetMapping("/search")
    public LedgerEntrySearchPageResponse searchEntries(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) EntryType entryType,
            @RequestParam(required = false) Long paymentMethodId,
            @RequestParam(required = false) Long categoryGroupId,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(defaultValue = "DATE_DESC") String sortBy,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size
    ) {
        return ledgerEntryService.searchEntries(
                currentUser.userId(),
                from,
                to,
                keyword,
                entryType,
                paymentMethodId,
                categoryGroupId,
                minAmount,
                maxAmount,
                sortBy,
                page,
                size
        );
    }

    @GetMapping("/date-range")
    public LedgerEntryDateRangeResponse getEntryDateRange(
            @AuthenticationPrincipal AppUserPrincipal currentUser
    ) {
        return ledgerEntryService.getEntryDateRange(currentUser.userId());
    }

    @GetMapping("/trash")
    public LedgerEntryPageResponse getDeletedEntries(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size
    ) {
        return ledgerEntryService.getDeletedEntries(currentUser.userId(), page, size);
    }

    @PostMapping("/export/csv")
    public ResponseEntity<ByteArrayResource> exportEntriesCsv(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody LedgerCsvExportRequest request,
            HttpServletRequest httpRequest
    ) {
        String verifiedSecondaryPin = SecondaryPinSessionSupport.getVerifiedSecondaryPin(httpRequest);
        if (verifiedSecondaryPin == null || verifiedSecondaryPin.isBlank()) {
            throw new BadRequestException("CSV를 저장하려면 2차 비밀번호가 검증된 로그인 세션이 필요합니다. 다시 로그인해 주세요.");
        }
        LedgerEntryService.LedgerProtectedExport export = ledgerEntryService.exportEntriesCsvProtected(
                currentUser.userId(),
                request.from(),
                request.to(),
                verifiedSecondaryPin
        );
        String encodedFileName = URLEncoder.encode(export.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                .contentType(MediaType.parseMediaType(export.contentType()))
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
    public void deleteEntry(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean permanent
    ) {
        ledgerEntryService.delete(currentUser.userId(), id, permanent);
    }

    @PostMapping("/{id}/restore")
    public LedgerEntryResponse restoreEntry(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long id
    ) {
        return ledgerEntryService.restore(currentUser.userId(), id);
    }
}
