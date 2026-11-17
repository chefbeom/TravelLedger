package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.DataPortabilityExportRequest;
import com.playdata.calen.account.dto.AccountWithdrawalPreviewResponse;
import com.playdata.calen.account.dto.AccountWithdrawalStartRequest;
import com.playdata.calen.account.dto.ProfileSecondaryPinVerifyRequest;
import com.playdata.calen.account.dto.PrivacyCleanupResponse;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.security.SecondaryPinSessionSupport;
import com.playdata.calen.account.service.DataPortabilityExportService;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.PrivacyManagementService;
import com.playdata.calen.account.service.SocialAccountService;
import com.playdata.calen.account.social.KakaoOAuthClient;
import com.playdata.calen.account.withdrawal.IntegrationWithdrawalGateway;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.familyalbum.service.FamilyAlbumWithdrawalService;
import com.playdata.calen.travel.service.TravelShareGroupWithdrawalService;
import com.playdata.calen.travel.service.TravelPlanWithdrawalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.beans.factory.ObjectProvider;
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
    private final AppUserService appUserService;
    private final SocialAccountService socialAccountService;
    private final KakaoOAuthClient kakaoOAuthClient;
    private final FamilyAlbumWithdrawalService familyAlbumWithdrawalService;
    private final TravelShareGroupWithdrawalService travelShareGroupWithdrawalService;
    private final TravelPlanWithdrawalService travelPlanWithdrawalService;
    private final ObjectProvider<IntegrationWithdrawalGateway> withdrawalGateway;
    private final PersistentTokenBasedRememberMeServices rememberMeServices;

    @PostMapping("/withdrawal/preview")
    public ResponseEntity<AccountWithdrawalPreviewResponse> previewWithdrawal(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody ProfileSecondaryPinVerifyRequest request
    ) {
        appUserService.verifySecondaryPin(currentUser.userId(), request.secondaryPin());
        boolean linked = socialAccountService.isKakaoLinked(currentUser.userId());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(
                new AccountWithdrawalPreviewResponse(withdrawalGateway.getIfAvailable() != null, linked, linked && kakaoOAuthClient.isUnlinkConfigured(),
                        familyAlbumWithdrawalService.requiredTransfers(currentUser.userId()),
                        travelShareGroupWithdrawalService.requiredTransfers(currentUser.userId()),
                        travelPlanWithdrawalService.requiredTransfers(currentUser.userId()),
                        travelPlanWithdrawalService.foreignMediaSummary(currentUser.userId())));
    }

    @PostMapping("/withdrawal")
    public ResponseEntity<java.util.Map<String, Object>> startWithdrawal(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody AccountWithdrawalStartRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        IntegrationWithdrawalGateway gateway = withdrawalGateway.getIfAvailable();
        if (gateway == null) throw new ServiceUnavailableException("회원 탈퇴 기능을 준비 중입니다.");
        gateway.submit(currentUser.userId(), request);
        // The database transaction has committed. Do not report it as failed if
        // browser/session cleanup fails afterwards; other requests recheck activity.
        try { rememberMeServices.logout(httpRequest, httpResponse, null); } catch (RuntimeException ignored) { }
        SecurityContextHolder.clearContext();
        try {
            HttpSession session = httpRequest.getSession(false);
            if (session != null) session.invalidate();
        } catch (RuntimeException ignored) { }
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store").body(java.util.Map.of(
                "accepted", true, "message", "탈퇴 요청을 접수했습니다. 자료 정리 완료는 별도로 확인해야 합니다."));
    }

    @DeleteMapping("/ai-analysis-history")
    public PrivacyCleanupResponse deleteAiAnalysisHistories(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest
    ) {
        requireVerifiedSecondaryPin(httpRequest);
        return privacyManagementService.deleteAiAnalysisHistories(currentUser.userId());
    }

    @DeleteMapping("/public-download-links")
    public PrivacyCleanupResponse revokePublicDownloadLinks(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest
    ) {
        requireVerifiedSecondaryPin(httpRequest);
        return privacyManagementService.revokePublicDownloadLinks(currentUser.userId());
    }

    @DeleteMapping("/travel-public-media-shares")
    public PrivacyCleanupResponse revokeTravelPublicMediaShares(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest
    ) {
        requireVerifiedSecondaryPin(httpRequest);
        return privacyManagementService.revokeTravelPublicMediaShares(currentUser.userId());
    }

    @DeleteMapping("/photo-location-metadata")
    public PrivacyCleanupResponse removePhotoLocationMetadata(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest
    ) {
        requireVerifiedSecondaryPin(httpRequest);
        return privacyManagementService.removePhotoLocationMetadata(currentUser.userId());
    }

    @PostMapping("/cleanup")
    public PrivacyCleanupResponse cleanupSensitiveData(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest
    ) {
        requireVerifiedSecondaryPin(httpRequest);
        return privacyManagementService.cleanupSensitiveData(currentUser.userId());
    }

    private String requireVerifiedSecondaryPin(HttpServletRequest httpRequest) {
        String verifiedSecondaryPin = secondaryPinSessionSupport.getVerifiedSecondaryPin(httpRequest);
        if (verifiedSecondaryPin == null || verifiedSecondaryPin.isBlank()) {
            throw new BadRequestException("개인정보 관리 작업을 실행하려면 본인 확인을 다시 해 주세요.");
        }
        return verifiedSecondaryPin;
    }
    @PostMapping("/data-export")
    public ResponseEntity<ByteArrayResource> exportUserDataArchive(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody DataPortabilityExportRequest request,
            HttpServletRequest httpRequest
    ) {
        String verifiedSecondaryPin = requireVerifiedSecondaryPin(httpRequest);
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
