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

    @DeleteMapping("/travel-public-media-shares")
    public PrivacyCleanupResponse revokeTravelPublicMediaShares(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return privacyManagementService.revokeTravelPublicMediaShares(currentUser.userId());
    }

    @PostMapping("/cleanup")
    public PrivacyCleanupResponse cleanupSensitiveData(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return privacyManagementService.cleanupSensitiveData(currentUser.userId());
    }

    @PostMapping("/data-export")
    public ResponseEntity<ByteArrayResource> exportUserDataArchive(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody DataPortabilityExportRequest request,
            HttpServletRequest httpRequest
    ) {
        String verifiedSecondaryPin = secondaryPinSessionSupport.getVerifiedSecondaryPin(httpRequest);
        if (verifiedSecondaryPin == null || verifiedSecondaryPin.isBlank()) {
            throw new BadRequestException("\uB370\uC774\uD130 \uB0B4\uBCF4\uB0B4\uAE30\uB97C \uC2E4\uD589\uD558\uB824\uBA74 2\uCC28 \uBE44\uBC00\uBC88\uD638\uAC00 \uAC80\uC99D\uB41C \uB85C\uADF8\uC778 \uC138\uC158\uC774 \uD544\uC694\uD569\uB2C8\uB2E4.");
        }
        DataPortabilityExportService.UserDataArchive archive = dataPortabilityExportService.exportUserDataArchive(
                currentUser.userId(),
                request.from(),
                request.to(),
                verifiedSecondaryPin
        );
        String encodedFileName = URLEncoder.encode(archive.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                .contentType(MediaType.parseMediaType(archive.contentType()))
                .contentLength(archive.content().length)
                .body(new ByteArrayResource(archive.content()));
    }
}