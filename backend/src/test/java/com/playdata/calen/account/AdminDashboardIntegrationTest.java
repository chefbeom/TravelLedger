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
                .andExpect(jsonPath("$.message").value("로그인 정보가 올바르지 않습니다."));

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

    private MockHttpSession login(String loginId, String password, String secondaryPin) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", loginId,
                                "password", password,
                                "secondaryPin", secondaryPin,
                                "rememberDevice", false
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return (MockHttpSession) result.getRequest().getSession(false);
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
