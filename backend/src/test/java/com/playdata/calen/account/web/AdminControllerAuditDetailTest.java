package com.playdata.calen.account.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.dto.AdminRestoreBackupRequest;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AdminDataManagementService;
import com.playdata.calen.account.service.AdminPageAccessService;
import com.playdata.calen.account.service.AdminService;
import com.playdata.calen.account.service.LoginAuditLogService;
import com.playdata.calen.account.service.SupportInquiryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class AdminControllerAuditDetailTest {

    @Mock
    private AdminService adminService;

    @Mock
    private AdminDataManagementService adminDataManagementService;

    @Mock
    private AdminPageAccessService adminPageAccessService;

    @Mock
    private SupportInquiryService supportInquiryService;

    @Mock
    private LoginAuditLogService loginAuditLogService;

    @Test
    void restoreAuditDetailUsesBackupBaseFileNameOnly() {
        AdminController controller = controller();
        MockHttpServletRequest request = request();
        AppUserPrincipal principal = principal();

        controller.restoreBackup(
                principal,
                request,
                new AdminRestoreBackupRequest("C:\\ops\\restore\\calen-2026-06-30.sql.gz")
        );

        verify(loginAuditLogService).recordAdminAction(
                eq(1L),
                eq("admin"),
                eq("198.51.100.7"),
                eq("JUnit"),
                eq("DATA_RESTORE:calen-2026-06-30.sql.gz")
        );
    }

    @Test
    void uploadedRestoreAuditDetailUsesUploadedBackupBaseFileNameOnly() throws Exception {
        AdminController controller = controller();
        MockHttpServletRequest request = request();
        AppUserPrincipal principal = principal();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "/tmp/restore/uploaded-backup.sql",
                "application/sql",
                "select 1;".getBytes()
        );

        controller.restoreUploadedBackup(principal, request, file);

        verify(loginAuditLogService).recordAdminAction(
                eq(1L),
                eq("admin"),
                eq("198.51.100.7"),
                eq("JUnit"),
                eq("DATA_RESTORE_UPLOAD:uploaded-backup.sql")
        );
    }

    private AdminController controller() {
        return new AdminController(
                adminService,
                adminDataManagementService,
                adminPageAccessService,
                supportInquiryService,
                loginAuditLogService
        );
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.7");
        request.addHeader("User-Agent", "JUnit");
        return request;
    }

    private AppUserPrincipal principal() {
        return new AppUserPrincipal(1L, "admin", "admin", "password", AppUserRole.ADMIN, true);
    }
}
