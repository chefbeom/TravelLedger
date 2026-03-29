package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.AdminDashboardResponse;
import com.playdata.calen.account.dto.AdminUserActiveRequest;
import com.playdata.calen.account.dto.AdminUserSummaryResponse;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/dashboard")
    public AdminDashboardResponse getDashboard() {
        return adminService.getDashboard();
    }

    @PatchMapping("/users/{userId}/active")
    public AdminUserSummaryResponse updateUserActive(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long userId,
            @RequestBody AdminUserActiveRequest request
    ) {
        return adminService.updateUserActive(currentUser.userId(), userId, request.active());
    }

    @DeleteMapping("/blocked-ips")
    public ResponseEntity<Void> clearBlockedIp(@RequestParam("ip") String ip) {
        adminService.clearBlockedIp(ip);
        return ResponseEntity.noContent().build();
    }
}
