package com.playdata.calen.drive.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AdminPageAccessService;
import jakarta.servlet.http.HttpServletRequest;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.service.DriveAdminService;
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
        return driveAdminService.updateUserStatus(
                currentUser != null && currentUser.isAdmin(),
                userId,
                request != null && request.active()
        );
    }

    @PatchMapping("/storage-capacity")
    public DriveDtos.StorageAnalyticsResponse updateStorageCapacity(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest,
            @RequestBody DriveDtos.StorageCapacityUpdateRequest request
    ) {
        requireVerifiedAdmin(httpRequest, currentUser);
        return driveAdminService.updateProviderCapacity(
                currentUser != null && currentUser.isAdmin(),
                request != null ? request.providerCapacityBytes() : null
        );
    }

    private void requireVerifiedAdmin(HttpServletRequest httpRequest, AppUserPrincipal currentUser) {
        adminPageAccessService.requireVerified(httpRequest, currentUser != null ? currentUser.userId() : null);
    }
}
