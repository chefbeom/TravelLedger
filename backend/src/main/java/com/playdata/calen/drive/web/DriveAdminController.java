package com.playdata.calen.drive.web;

import com.playdata.calen.account.security.AppUserPrincipal;
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

    @GetMapping("/dashboard")
    public DriveDtos.AdminDashboardResponse getDashboard(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return driveAdminService.getDashboard(currentUser != null && currentUser.isAdmin());
    }

    @GetMapping("/storage-analytics")
    public DriveDtos.StorageAnalyticsResponse getStorageAnalytics(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return driveAdminService.getStorageAnalytics(currentUser != null && currentUser.isAdmin());
    }

    @PatchMapping("/users/{userId}/status")
    public DriveDtos.AdminUserResponse updateUserStatus(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long userId,
            @RequestBody DriveDtos.AdminUserResponse request
    ) {
        return driveAdminService.updateUserStatus(
                currentUser != null && currentUser.isAdmin(),
                userId,
                request != null && request.active()
        );
    }

    @PatchMapping("/storage-capacity")
    public DriveDtos.StorageAnalyticsResponse updateStorageCapacity(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestBody DriveDtos.StorageCapacityUpdateRequest request
    ) {
        return driveAdminService.updateProviderCapacity(
                currentUser != null && currentUser.isAdmin(),
                request != null ? request.providerCapacityBytes() : null
        );
    }
}
