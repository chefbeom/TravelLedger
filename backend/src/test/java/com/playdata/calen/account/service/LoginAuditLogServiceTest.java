package com.playdata.calen.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.domain.LoginAuditLog;
import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.LoginAuditLogRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoginAuditLogServiceTest {

    @Mock
    private LoginAuditLogRepository loginAuditLogRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Test
    void recordAdminActionStoresActorDetailAndAdminActionStatus() {
        AppUser admin = new AppUser();
        admin.setId(1L);
        admin.setLoginId("admin");
        admin.setDisplayName("Admin");
        admin.setRole(AppUserRole.ADMIN);
        when(appUserRepository.findById(1L)).thenReturn(Optional.of(admin));

        LoginAuditLogService service = new LoginAuditLogService(loginAuditLogRepository, appUserRepository);

        service.recordAdminAction(
                1L,
                "admin",
                "203.0.113.10",
                "JUnit",
                "DATA_RESTORE:calen-2026.sql.gz"
        );

        ArgumentCaptor<LoginAuditLog> captor = ArgumentCaptor.forClass(LoginAuditLog.class);
        verify(loginAuditLogRepository).save(captor.capture());
        LoginAuditLog saved = captor.getValue();

        assertThat(saved.getLoginId()).isEqualTo("admin");
        assertThat(saved.getClientIp()).isEqualTo("203.0.113.10");
        assertThat(saved.getUserAgent()).isEqualTo("JUnit");
        assertThat(saved.getStatus()).isEqualTo(LoginAuditStatus.ADMIN_ACTION);
        assertThat(saved.isSuccess()).isTrue();
        assertThat(saved.getDetail()).isEqualTo("DATA_RESTORE:calen-2026.sql.gz");
        assertThat(saved.getAppUser()).isSameAs(admin);
    }
}