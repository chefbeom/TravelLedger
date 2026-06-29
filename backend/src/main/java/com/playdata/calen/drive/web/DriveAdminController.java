package com.playdata.calen.drive.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AdminPageAccessService;
import com.playdata.calen.account.service.LoginAuditLogService;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.service.DriveAdminService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/administrator")
@RequiredArgsConstructor
public class DriveAdminController {

    private final DriveAdminService driveAdminService;
    private final AdminPageAccessService adminPageAccessService;
    private final LoginAuditLogService loginAuditLogService;

    @GetMapping("/dashboard")
    public DriveDtos.AdminDashboardResponse getDashboard(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest
    ) {
        requireVerifiedAdmin(httpRequest, currentUser);
        return driveAdminService.getDashboard(currentUser != null && currentUser.isAdmin());
    }

    @GetMapping("/storage-analytics")
    public DriveDtos.StorageAnalyticsResponse getStorageAnalytics(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest
    ) {
        requireVerifiedAdmin(httpRequest, currentUser);
        return driveAdminService.getStorageAnalytics(currentUser != null && currentUser.isAdmin());
    }

    @PatchMapping("/users/{userId}/status")
    public DriveDtos.AdminUserResponse updateUserStatus(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest,
            @PathVariable Long userId,
            @RequestBody DriveDtos.AdminUserResponse request
    ) {
        requireVerifiedAdmin(httpRequest, currentUser);
        boolean active = request != null && request.active();
        DriveDtos.AdminUserResponse response = driveAdminService.updateUserStatus(
                currentUser != null && currentUser.isAdmin(),
                userId,
                active
        );
        recordAdminAction(currentUser, httpRequest, "DRIVE_USER_STATUS_UPDATE:userId=" + userId + ",active=" + active);
        return response;
    }

    @PatchMapping("/storage-capacity")
    public DriveDtos.StorageAnalyticsResponse updateStorageCapacity(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest,
            @RequestBody DriveDtos.StorageCapacityUpdateRequest request
    ) {
        requireVerifiedAdmin(httpRequest, currentUser);
        Long providerCapacityBytes = request != null ? request.providerCapacityBytes() : null;
        DriveDtos.StorageAnalyticsResponse response = driveAdminService.updateProviderCapacity(
                currentUser != null && currentUser.isAdmin(),
                providerCapacityBytes
        );
        recordAdminAction(currentUser, httpRequest, "DRIVE_STORAGE_CAPACITY_UPDATE:providerCapacityBytes=" + providerCapacityBytes);
        return response;
    }

    private void recordAdminAction(AppUserPrincipal currentUser, HttpServletRequest httpRequest, String detail) {
        loginAuditLogService.recordAdminAction(
                currentUser != null ? currentUser.userId() : null,
                currentUser != null ? currentUser.loginId() : null,
                resolveClientIp(httpRequest),
                httpRequest != null ? httpRequest.getHeader("User-Agent") : null,
                detail
        );
    }

    private String resolveClientIp(HttpServletRequest httpRequest) {
        if (httpRequest == null) {
            return "drive-admin-api";
        }
        String forwardedFor = httpRequest.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return httpRequest.getRemoteAddr();
    }

    private void requireVerifiedAdmin(HttpServletRequest httpRequest, AppUserPrincipal currentUser) {
        adminPageAccessService.requireVerified(httpRequest, currentUser != null ? currentUser.userId() : null);
    }
}