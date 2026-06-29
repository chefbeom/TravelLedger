package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.DataPortabilityExportRequest;
import com.playdata.calen.account.dto.PrivacyCleanupResponse;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.security.SecondaryPinSessionSupport;
import com.playdata.calen.account.service.DataPortabilityExportService;
import com.playdata.calen.account.service.PrivacyManagementService;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/privacy")
@RequiredArgsConstructor
public class PrivacyController {

    private final PrivacyManagementService privacyManagementService;
    private final DataPortabilityExportService dataPortabilityExportService;
    private final SecondaryPinSessionSupport secondaryPinSessionSupport;

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