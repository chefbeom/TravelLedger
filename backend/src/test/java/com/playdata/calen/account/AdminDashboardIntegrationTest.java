package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "app.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:admin-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class AdminDashboardIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void adminCanReadDashboardButRegularUserCannot() throws Exception {
        MockHttpSession adminSession = login("admin", "test1234", "12345678");
        MockHttpSession userSession = login("hana", "test1234", "12345678");

        mockMvc.perform(get("/api/admin/dashboard").session(adminSession))
                .andExpect(status().isForbidden());

        verifyAdminAccess(adminSession);

        mockMvc.perform(get("/api/admin/dashboard").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.adminUsers").value(1))
                .andExpect(jsonPath("$.recentLoginLogs").isArray())
                .andExpect(jsonPath("$.recentLoginLogs").isNotEmpty())
                .andExpect(jsonPath("$.users.length()").isNotEmpty());

        mockMvc.perform(get("/api/admin/dashboard").session(userSession))
                .andExpect(status().isForbidden());
    }

    @Test
    void sensitiveDataManagementApisRequireAuthenticatedVerifiedAdmin() throws Exception {
        MockHttpSession adminSession = login("admin", "test1234", "12345678");
        MockHttpSession userSession = login("hana", "test1234", "12345678");

        mockMvc.perform(get("/api/admin/data-management"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/data-management/backup")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/data-management").session(adminSession))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/data-management/backup")
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/data-management").session(userSession))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/data-management/backup")
                        .session(userSession)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void dashboardShowsOnlyTenLoginLogsAndSupportsPaging() throws Exception {
        MockHttpSession adminSession = login("admin", "test1234", "12345678");
        verifyAdminAccess(adminSession);
        for (int index = 0; index < 11; index++) {
            login("admin", "test1234", "12345678");
        }

        mockMvc.perform(get("/api/admin/dashboard").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentLoginLogs.length()").value(10))
                .andExpect(jsonPath("$.recentLoginLogTotalElements").value(12))
                .andExpect(jsonPath("$.recentLoginLogTotalPages").value(2));

        mockMvc.perform(get("/api/admin/login-audit-logs")
                        .param("page", "1")
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void adminUserActiveMutationRequiresVerifiedAdminAndCsrf() throws Exception {
        MockHttpSession verifiedAdminSession = login("admin", "test1234", "12345678");
        MockHttpSession unverifiedAdminSession = login("admin", "test1234", "12345678");
        MockHttpSession userSession = login("hana", "test1234", "12345678");
        verifyAdminAccess(verifiedAdminSession);
        long hanaUserId = findUserId(verifiedAdminSession, "hana");

        mockMvc.perform(patch("/api/admin/users/{userId}/active", hanaUserId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", true))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/admin/users/{userId}/active", hanaUserId)
                        .session(userSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", true))))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/admin/users/{userId}/active", hanaUserId)
                        .session(unverifiedAdminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", true))))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/admin/users/{userId}/active", hanaUserId)
                        .session(verifiedAdminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", true))))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/admin/users/{userId}/active", hanaUserId)
                        .session(verifiedAdminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("hana"))
                .andExpect(jsonPath("$.active").value(true));
    }
    @Test
    @DirtiesContext
    void adminDeactivationRevokesRememberMeTokens() throws Exception {
        MockHttpSession adminSession = login("admin", "test1234", "12345678");
        verifyAdminAccess(adminSession);
        long minsuUserId = findUserId(adminSession, "minsu");

        MvcResult minsuLoginResult = loginResult("minsu", "test1234", "87654321", true);
        Cookie rememberMeCookie = minsuLoginResult.getResponse().getCookie("CALEN_REMEMBER_ME");
        assertThat(rememberMeCookie).isNotNull();
        assertThat(rememberMeCookie.getValue()).isNotBlank();

        mockMvc.perform(patch("/api/admin/users/{userId}/active", minsuUserId)
                        .session(adminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("minsu"))
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/auth/me").cookie(rememberMeCookie))
                .andExpect(status().isUnauthorized());
    }
    @Test
    void adminCannotDeactivateAdminAccountAndFailedLoginReasonStaysGeneric() throws Exception {
        MockHttpSession adminSession = login("admin", "test1234", "12345678");
        verifyAdminAccess(adminSession);

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", "hana",
                                "password", "test1234",
                                "secondaryPin", "00000000",
                                "rememberDevice", false
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("\uB85C\uADF8\uC778 \uC815\uBCF4\uAC00 \uC62C\uBC14\uB974\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4."));

        mockMvc.perform(get("/api/admin/login-audit-logs").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("FAILED"));

        MvcResult dashboardResult = mockMvc.perform(get("/api/admin/dashboard").session(adminSession))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode users = objectMapper.readTree(dashboardResult.getResponse().getContentAsString()).get("users");
        long adminUserId = -1L;
        for (JsonNode user : users) {
            if ("admin".equals(user.get("loginId").asText())) {
                adminUserId = user.get("id").asLong();
                break;
            }
        }
        assertThat(adminUserId).isPositive();

        mockMvc.perform(patch("/api/admin/users/{userId}/active", adminUserId)
                        .session(adminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isBadRequest());
    }

    private long findUserId(MockHttpSession adminSession, String loginId) throws Exception {
        MvcResult dashboardResult = mockMvc.perform(get("/api/admin/dashboard").session(adminSession))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode users = objectMapper.readTree(dashboardResult.getResponse().getContentAsString()).get("users");
        for (JsonNode user : users) {
            if (loginId.equals(user.get("loginId").asText())) {
                return user.get("id").asLong();
            }
        }
        throw new AssertionError("Seed user not found: " + loginId);
    }
    private MockHttpSession login(String loginId, String password, String secondaryPin) throws Exception {
        MvcResult result = loginResult(loginId, password, secondaryPin, false);
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MvcResult loginResult(String loginId, String password, String secondaryPin, boolean rememberDevice) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", loginId,
                                "password", password,
                                "secondaryPin", secondaryPin,
                                "rememberDevice", rememberDevice
                        ))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private void verifyAdminAccess(MockHttpSession session) throws Exception {
        String code = String.valueOf(
                19990515 + Integer.parseInt(LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE))
        );

        mockMvc.perform(post("/api/admin/access/verify")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", code))))
                .andExpect(status().isNoContent());
    }
}
