package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.AdminDashboardResponse;
import com.playdata.calen.account.dto.AdminDataManagementResponse;
import com.playdata.calen.account.dto.AdminDataStorageControlUpdateRequest;
import com.playdata.calen.account.dto.AdminLoginAuditPageResponse;
import com.playdata.calen.account.dto.AdminAiControlUpdateRequest;
import com.playdata.calen.account.dto.AdminOpsControlResponse;
import com.playdata.calen.account.dto.SupportInquiryArchiveRequest;
import com.playdata.calen.account.dto.SupportInquiryReplyRequest;
import com.playdata.calen.account.dto.SupportInquiryResponse;
import com.playdata.calen.account.dto.SupportInquiryStatusUpdateRequest;
import com.playdata.calen.account.dto.AdminUserActiveRequest;
import com.playdata.calen.account.dto.AdminUserSummaryResponse;
import com.playdata.calen.account.dto.AdminBackupFileResponse;
import com.playdata.calen.account.dto.AdminRestoreBackupRequest;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AdminDataManagementService;
import com.playdata.calen.account.service.AdminService;
import com.playdata.calen.account.service.AdminPageAccessService;
import com.playdata.calen.account.service.AdminOpsControlService;
import com.playdata.calen.account.service.LoginAuditLogService;
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
    private final AdminOpsControlService adminOpsControlService;
    private final SupportInquiryService supportInquiryService;
    private final LoginAuditLogService loginAuditLogService;

    public AdminController(
            AdminService adminService,
            AdminDataManagementService adminDataManagementService,
            AdminPageAccessService adminPageAccessService,
            AdminOpsControlService adminOpsControlService,
            SupportInquiryService supportInquiryService,
            LoginAuditLogService loginAuditLogService
    ) {
        this.adminService = adminService;
        this.adminDataManagementService = adminDataManagementService;
        this.adminPageAccessService = adminPageAccessService;
        this.adminOpsControlService = adminOpsControlService;
        this.supportInquiryService = supportInquiryService;
        this.loginAuditLogService = loginAuditLogService;
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

    @GetMapping("/ops-control")
    public AdminOpsControlResponse getOpsControl() {
        return adminOpsControlService.getSnapshot();
    }

    @PatchMapping("/ops-control/ai")
    public AdminOpsControlResponse updateAiControl(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest,
            @RequestBody AdminAiControlUpdateRequest request
    ) {
        AdminOpsControlResponse response = adminOpsControlService.updateAi(request);
        recordAdminAction(currentUser, httpRequest, "AI_CONTROL_UPDATE:enabled=" + response.ai().enabled() + ",provider=" + safeDetail(response.ai().provider()) + ",model=" + safeDetail(response.ai().model()));
        return response;
    }

    @PatchMapping("/ops-control/data-storage")
    public AdminOpsControlResponse updateDataStorageControl(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest,
            @RequestBody AdminDataStorageControlUpdateRequest request
    ) {
        AdminOpsControlResponse response = adminOpsControlService.updateDataStorage(request);
        recordAdminAction(currentUser, httpRequest, "DATA_STORAGE_CONTROL_UPDATE:capacityBytes=" + response.dataServer().minioStorage().capacityBytes());
        return response;
    }

    @PostMapping("/data-management/backup")
    public AdminBackupFileResponse createManualBackup(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest
    ) {
        AdminBackupFileResponse response = adminDataManagementService.createManualBackup();
        recordAdminAction(currentUser, httpRequest, "DATA_BACKUP_CREATE:" + safeBackupFileName(response.fileName()));
        return response;
    }

    @PostMapping("/data-management/minio-backup")
    public AdminBackupFileResponse createManualMinioBackup(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest
    ) {
        AdminBackupFileResponse response = adminDataManagementService.createManualMinioBackup();
        recordAdminAction(currentUser, httpRequest, "MINIO_BACKUP_CREATE:" + safeBackupFileName(response.fileName()));
        return response;
    }

    @PostMapping("/data-management/backup/download")
    public ResponseEntity<StreamingResponseBody> downloadCurrentBackup(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest
    ) {
        AdminDataManagementService.PreparedBackupDownload preparedBackup = adminDataManagementService.createDownloadableBackup();
        recordAdminAction(currentUser, httpRequest, "DATA_BACKUP_DOWNLOAD:" + safeBackupFileName(preparedBackup.fileName()));
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
    public ResponseEntity<Void> restoreBackup(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest,
            @Valid @RequestBody AdminRestoreBackupRequest request
    ) {
        adminDataManagementService.restoreBackup(request.fileName());
        recordAdminAction(currentUser, httpRequest, "DATA_RESTORE:" + safeBackupFileName(request.fileName()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/data-management/restore/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> restoreUploadedBackup(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        adminDataManagementService.restoreUploadedBackup(file);
        recordAdminAction(currentUser, httpRequest, "DATA_RESTORE_UPLOAD:" + safeBackupFileName(file.getOriginalFilename()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/login-audit-logs")
    public AdminLoginAuditPageResponse getLoginAuditLogs(@RequestParam(name = "page", defaultValue = "0") int page) {
        return adminService.getLoginAuditLogs(page);
    }

    @PatchMapping("/users/{userId}/active")
    public AdminUserSummaryResponse updateUserActive(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest,
            @PathVariable Long userId,
            @RequestBody AdminUserActiveRequest request
    ) {
        AdminUserSummaryResponse response = adminService.updateUserActive(currentUser.userId(), userId, request.active());
        recordAdminAction(currentUser, httpRequest, "USER_ACTIVE_UPDATE:userId=" + userId + ",active=" + request.active());
        return response;
    }

    @DeleteMapping("/blocked-ips")
    public ResponseEntity<Void> clearBlockedIp(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest,
            @RequestParam("ip") String ip
    ) {
        adminService.clearBlockedIp(ip);
        recordAdminAction(currentUser, httpRequest, "BLOCKED_IP_CLEAR:" + safeDetail(ip));
        return ResponseEntity.noContent().build();
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
            return "admin-api";
        }
        String forwardedFor = httpRequest.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return httpRequest.getRemoteAddr();
    }


    private String safeBackupFileName(String value) {
        String detail = safeDetail(value);
        if ("-".equals(detail)) {
            return detail;
        }
        String normalized = detail.replace('\\', '/');
        int separatorIndex = normalized.lastIndexOf('/');
        return separatorIndex >= 0 ? normalized.substring(separatorIndex + 1) : normalized;
    }
    private String safeDetail(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
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

    @PatchMapping("/support-inquiries/{inquiryId}/status")
    public SupportInquiryResponse updateSupportInquiryStatus(
            @PathVariable Long inquiryId,
            @RequestBody SupportInquiryStatusUpdateRequest request
    ) {
        return supportInquiryService.updateStatus(inquiryId, request.status());
    }
    @DeleteMapping("/support-inquiries/{inquiryId}")
    public ResponseEntity<Void> deleteSupportInquiry(@PathVariable Long inquiryId) {
        supportInquiryService.deleteForAdmin(inquiryId);
        return ResponseEntity.noContent().build();
    }
}



