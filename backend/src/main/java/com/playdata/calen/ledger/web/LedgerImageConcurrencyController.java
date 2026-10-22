package com.playdata.calen.ledger.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AdminPageAccessService;
import com.playdata.calen.account.service.LoginAuditLogService;
import com.playdata.calen.ledger.dto.LedgerImageConcurrencyResponse;
import com.playdata.calen.ledger.dto.LedgerImageConcurrencyUpdateRequest;
import com.playdata.calen.ledger.ocr.LedgerImageConcurrencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class LedgerImageConcurrencyController {
    private final LedgerImageConcurrencyService settings;
    private final AdminPageAccessService adminAccess;
    private final LoginAuditLogService audit;

    @GetMapping("/api/ledger/image-analysis/settings")
    @PreAuthorize("isAuthenticated()")
    public LedgerImageConcurrencyResponse settings() {
        return settings.getSettings();
    }

    @GetMapping("/api/admin/ops-control/image-analysis")
    @PreAuthorize("hasRole('ADMIN')")
    public LedgerImageConcurrencyResponse adminSettings(@AuthenticationPrincipal AppUserPrincipal user, HttpServletRequest request) {
        adminAccess.requireVerified(request, user.userId());
        return settings.getSettings();
    }

    @PatchMapping("/api/admin/ops-control/image-analysis")
    @PreAuthorize("hasRole('ADMIN')")
    public LedgerImageConcurrencyResponse update(@AuthenticationPrincipal AppUserPrincipal user, HttpServletRequest request,
            @Valid @RequestBody LedgerImageConcurrencyUpdateRequest update) {
        adminAccess.requireVerified(request, user.userId());
        var response = settings.update(update.maxConcurrent().intValueExact());
        audit.recordAdminAction(user.userId(), user.loginId(), request.getRemoteAddr(), request.getHeader("User-Agent"),
                "IMAGE_ANALYSIS_CONCURRENCY_UPDATE:maxConcurrent=" + response.maxConcurrent());
        return response;
    }
}
