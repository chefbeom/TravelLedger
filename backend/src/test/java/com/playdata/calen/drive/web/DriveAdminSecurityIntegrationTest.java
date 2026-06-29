package com.playdata.calen.drive.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.domain.LoginAuditLog;
import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.account.repository.LoginAuditLogRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:drive-admin-security-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class DriveAdminSecurityIntegrationTest {

    private static final String VERIFIED_ADMIN_USER_ID_KEY = "VERIFIED_ADMIN_USER_ID";
    private static final String VERIFIED_ADMIN_DATE_KEY = "VERIFIED_ADMIN_DATE";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoginAuditLogRepository loginAuditLogRepository;

    @Test
    void administratorApisRequireAuthenticationAdminRoleAndRecentVerification() throws Exception {
        AppUserPrincipal admin = principal(1L, "admin", AppUserRole.ADMIN);
        AppUserPrincipal regularUser = principal(2L, "hana", AppUserRole.USER);

        mockMvc.perform(get("/api/administrator/dashboard"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/administrator/dashboard").with(user(regularUser)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/administrator/dashboard")
                        .with(user(regularUser))
                        .session(verifiedSession(regularUser.userId())))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/administrator/dashboard").with(user(admin)))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/administrator/storage-capacity")
                        .with(user(admin))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("providerCapacityBytes", 1_000_000L))))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/administrator/storage-capacity")
                        .with(user(admin))
                        .session(verifiedSession(admin.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("providerCapacityBytes", 2_000_000L))))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/administrator/storage-capacity")
                        .with(user(admin))
                        .with(csrf())
                        .session(verifiedSession(admin.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("providerCapacityBytes", 2_000_000L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerCapacityBytes").value(2_000_000L));

        LoginAuditLog auditLog = loginAuditLogRepository.findAll().stream()
                .filter(log -> log.getStatus() == LoginAuditStatus.ADMIN_ACTION)
                .filter(log -> "admin".equals(log.getLoginId()))
                .filter(log -> log.getDetail() != null && log.getDetail().startsWith("DRIVE_STORAGE_CAPACITY_UPDATE"))
                .max(Comparator.comparing(LoginAuditLog::getId))
                .orElseThrow();
        assertThat(auditLog.getDetail()).isEqualTo("DRIVE_STORAGE_CAPACITY_UPDATE:providerCapacityBytes=2000000");
        assertThat(auditLog.getDetail()).doesNotContain("password", "token", "key");

        mockMvc.perform(get("/api/administrator/dashboard")
                        .with(user(admin))
                        .session(verifiedSession(admin.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalUserCount").isNumber());
    }


    @Test
    void administratorUserStatusMutationRequiresCsrfAndRecordsSafeAuditDetail() throws Exception {
        AppUserPrincipal admin = principal(1L, "admin", AppUserRole.ADMIN);

        mockMvc.perform(patch("/api/administrator/users/{userId}/status", 2L)
                        .with(user(admin))
                        .session(verifiedSession(admin.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/administrator/users/{userId}/status", 2L)
                        .with(user(admin))
                        .with(csrf())
                        .session(verifiedSession(admin.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2L))
                .andExpect(jsonPath("$.active").value(false));

        LoginAuditLog auditLog = loginAuditLogRepository.findAll().stream()
                .filter(log -> log.getStatus() == LoginAuditStatus.ADMIN_ACTION)
                .filter(log -> "admin".equals(log.getLoginId()))
                .filter(log -> log.getDetail() != null && log.getDetail().startsWith("DRIVE_USER_STATUS_UPDATE"))
                .max(Comparator.comparing(LoginAuditLog::getId))
                .orElseThrow();
        assertThat(auditLog.getDetail()).isEqualTo("DRIVE_USER_STATUS_UPDATE:userId=2,active=false");
        assertThat(auditLog.getDetail()).doesNotContain("password", "token", "key");
    }
    private AppUserPrincipal principal(Long userId, String loginId, AppUserRole role) {
        return new AppUserPrincipal(userId, loginId, loginId, "password", role, true);
    }

    private MockHttpSession verifiedSession(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(VERIFIED_ADMIN_USER_ID_KEY, userId);
        session.setAttribute(VERIFIED_ADMIN_DATE_KEY, currentDateString());
        return session;
    }

    private String currentDateString() {
        return LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}