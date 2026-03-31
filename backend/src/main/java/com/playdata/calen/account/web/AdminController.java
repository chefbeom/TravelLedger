package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.AdminDashboardResponse;
import com.playdata.calen.account.dto.AdminLoginAuditPageResponse;
import com.playdata.calen.account.dto.SupportInquiryArchiveRequest;
import com.playdata.calen.account.dto.SupportInquiryReplyRequest;
import com.playdata.calen.account.dto.SupportInquiryResponse;
import com.playdata.calen.account.dto.AdminUserActiveRequest;
import com.playdata.calen.account.dto.AdminUserSummaryResponse;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AdminService;
import com.playdata.calen.account.service.AdminPageAccessService;
import com.playdata.calen.account.service.SupportInquiryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ModelAttribute;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;
    private final AdminPageAccessService adminPageAccessService;
    private final SupportInquiryService supportInquiryService;

    public AdminController(
            AdminService adminService,
            AdminPageAccessService adminPageAccessService,
            SupportInquiryService supportInquiryService
    ) {
        this.adminService = adminService;
        this.adminPageAccessService = adminPageAccessService;
        this.supportInquiryService = supportInquiryService;
    }

    @ModelAttribute
    public void ensureAdminAccessVerified(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest
    ) {
        adminPageAccessService.requireVerified(httpRequest, currentUser.userId());
    }

    @GetMapping("/dashboard")
    public AdminDashboardResponse getDashboard() {
        return adminService.getDashboard();
    }

    @GetMapping("/login-audit-logs")
    public AdminLoginAuditPageResponse getLoginAuditLogs(@RequestParam(name = "page", defaultValue = "0") int page) {
        return adminService.getLoginAuditLogs(page);
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

    @GetMapping("/support-inquiries")
    public List<SupportInquiryResponse> getSupportInquiries() {
        return supportInquiryService.getAdminInbox();
    }

    @PutMapping("/support-inquiries/{inquiryId}/reply")
    public SupportInquiryResponse replySupportInquiry(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long inquiryId,
            @Valid @RequestBody SupportInquiryReplyRequest request
    ) {
        return supportInquiryService.reply(currentUser.userId(), inquiryId, request.content());
    }

    @PatchMapping("/support-inquiries/{inquiryId}/archive")
    public SupportInquiryResponse archiveSupportInquiry(
            @PathVariable Long inquiryId,
            @RequestBody SupportInquiryArchiveRequest request
    ) {
        return supportInquiryService.setArchived(inquiryId, request.archived());
    }

    @DeleteMapping("/support-inquiries/{inquiryId}")
    public ResponseEntity<Void> deleteSupportInquiry(@PathVariable Long inquiryId) {
        supportInquiryService.deleteForAdmin(inquiryId);
        return ResponseEntity.noContent().build();
    }
}
