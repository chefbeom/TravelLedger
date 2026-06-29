package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.PrivacyCleanupResponse;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.PrivacyManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/privacy")
@RequiredArgsConstructor
public class PrivacyController {

    private final PrivacyManagementService privacyManagementService;

    @DeleteMapping("/ai-analysis-history")
    public PrivacyCleanupResponse deleteAiAnalysisHistories(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return privacyManagementService.deleteAiAnalysisHistories(currentUser.userId());
    }

    @DeleteMapping("/public-download-links")
    public PrivacyCleanupResponse revokePublicDownloadLinks(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return privacyManagementService.revokePublicDownloadLinks(currentUser.userId());
    }

    @PostMapping("/cleanup")
    public PrivacyCleanupResponse cleanupSensitiveData(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return privacyManagementService.cleanupSensitiveData(currentUser.userId());
    }
}