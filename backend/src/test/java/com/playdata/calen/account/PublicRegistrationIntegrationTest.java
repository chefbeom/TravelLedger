package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.repository.AppUserRepository;
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
        "app.seed.allow-insecure-default-credentials=true",
        "spring.datasource.url=jdbc:h2:mem:registration-policy-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class PublicRegistrationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository appUserRepository;

    @Test
    void adminCanTogglePublicRegistrationWhileInvitationSignupRemainsAvailable() throws Exception {
        mockMvc.perform(get("/api/auth/registration-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicRegistrationEnabled").value(false))
                .andExpect(jsonPath("$.socialLoginProviders").isArray())
                .andExpect(jsonPath("$.socialLoginProviders").isEmpty());

        mockMvc.perform(publicRegistration("closed-public-user"))
                .andExpect(status().isForbidden());

        MockHttpSession regularUserSession = login("hana", "test1234", "12345678");
        mockMvc.perform(get("/api/admin/registration-policy").session(regularUserSession))
                .andExpect(status().isForbidden());

        MockHttpSession adminSession = login("admin", "test1234", "12345678");
        verifyAdminAccess(adminSession);
        mockMvc.perform(put("/api/admin/registration-policy")
                        .session(adminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("publicRegistrationEnabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicRegistrationEnabled").value(true));

        mockMvc.perform(publicRegistration("public-user"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginId").value("public-user"))
                .andExpect(jsonPath("$.admin").value(false));
        assertThat(appUserRepository.findByLoginId("public-user").orElseThrow().getRole())
                .isEqualTo(AppUserRole.USER);

        mockMvc.perform(put("/api/admin/registration-policy")
                        .session(adminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("publicRegistrationEnabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicRegistrationEnabled").value(false));

        mockMvc.perform(publicRegistration("closed-again-user"))
                .andExpect(status().isForbidden());

        String inviteToken = createInvite(adminSession);
        mockMvc.perform(post("/api/invites/accept")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", inviteToken,
                                "loginId", "invite-after-public-off",
                                "displayName", "Invite After Public Off",
                                "password", "strongpass1",
                                "secondaryPin", "23456789"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginId").value("invite-after-public-off"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicRegistration(String loginId) throws Exception {
        return post("/api/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "loginId", loginId,
                        "displayName", "Public User",
                        "password", "strongpass1",
                        "secondaryPin", "23456789",
                        "rememberDevice", false
                )));
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
        mockMvc.perform(post("/api/admin/access/verify")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "12345678"))))
                .andExpect(status().isNoContent());
    }

    private String createInvite(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/invites")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expiresInHours", 72))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }
}
