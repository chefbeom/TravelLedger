package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.AdminDashboardResponse;
import com.playdata.calen.account.dto.AdminDataManagementResponse;
import com.playdata.calen.account.dto.AdminLoginAuditPageResponse;
import com.playdata.calen.account.dto.SupportInquiryArchiveRequest;
import com.playdata.calen.account.dto.SupportInquiryReplyRequest;
import com.playdata.calen.account.dto.SupportInquiryResponse;
import com.playdata.calen.account.dto.AdminUserActiveRequest;
import com.playdata.calen.account.dto.AdminUserSummaryResponse;
import com.playdata.calen.account.dto.AdminBackupFileResponse;
import com.playdata.calen.account.dto.AdminRestoreBackupRequest;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AdminDataManagementService;
import com.playdata.calen.account.service.AdminService;
import com.playdata.calen.account.service.AdminPageAccessService;
import com.playdata.calen.account.service.SupportInquiryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;
    private final AdminDataManagementService adminDataManagementService;
    private final AdminPageAccessService adminPageAccessService;
    private final SupportInquiryService supportInquiryService;

    public AdminController(
            AdminService adminService,
            AdminDataManagementService adminDataManagementService,
            AdminPageAccessService adminPageAccessService,
            SupportInquiryService supportInquiryService
    ) {
        this.adminService = adminService;
        this.adminDataManagementService = adminDataManagementService;
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

    @GetMapping("/data-management")
    public AdminDataManagementResponse getDataManagement() {
        return adminDataManagementService.getSnapshot();
    }

    @PostMapping("/data-management/backup")
    public AdminBackupFileResponse createManualBackup() {
        return adminDataManagementService.createManualBackup();
    }

    @PostMapping("/data-management/backup/download")
    public ResponseEntity<StreamingResponseBody> downloadCurrentBackup() {
        AdminDataManagementService.PreparedBackupDownload preparedBackup = adminDataManagementService.createDownloadableBackup();
        StreamingResponseBody body = outputStream -> {
            try (InputStream inputStream = java.nio.file.Files.newInputStream(preparedBackup.path())) {
                inputStream.transferTo(outputStream);
            } finally {
                adminDataManagementService.cleanupPreparedBackup(preparedBackup);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(preparedBackup.fileName()).build().toString())
                .contentType(MediaType.parseMediaType("application/gzip"))
                .contentLength(preparedBackup.sizeBytes())
                .body(body);
    }

    @PostMapping("/data-management/restore")
    public ResponseEntity<Void> restoreBackup(@Valid @RequestBody AdminRestoreBackupRequest request) {
        adminDataManagementService.restoreBackup(request.fileName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/data-management/restore/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> restoreUploadedBackup(@RequestPart("file") MultipartFile file) throws IOException {
        adminDataManagementService.restoreUploadedBackup(file);
        return ResponseEntity.noContent().build();
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
