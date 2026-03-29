package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.util.LinkedHashMap;
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
        "spring.datasource.url=jdbc:h2:mem:calen-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class InviteFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private CategoryGroupRepository categoryGroupRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Test
    void inviteOnlySignupCreatesAccountAndConsumesInvite() throws Exception {
        MockHttpSession adminSession = loginAndGetSession("admin", "test1234", false);
        AppUser admin = appUserRepository.findByLoginId("admin").orElseThrow();

        String token = createInviteAndGetToken(adminSession);

        mockMvc.perform(get("/api/invites/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviterDisplayName").value(admin.getDisplayName()))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());

        Map<String, Object> acceptPayload = new LinkedHashMap<>();
        acceptPayload.put("token", token);
        acceptPayload.put("loginId", "invite-user");
        acceptPayload.put("displayName", "Invite User");
        acceptPayload.put("password", "strongpass1");
        acceptPayload.put("secondaryPin", "23456789");

        mockMvc.perform(post("/api/invites/accept")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginId").value("invite-user"))
                .andExpect(jsonPath("$.displayName").value("Invite User"));

        AppUser invitedUser = appUserRepository.findByLoginId("invite-user").orElseThrow();
        assertThat(categoryGroupRepository.existsByOwnerId(invitedUser.getId())).isTrue();
        assertThat(paymentMethodRepository.existsByOwnerId(invitedUser.getId())).isTrue();

        mockMvc.perform(post("/api/invites/accept")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptPayload)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", "invite-user",
                                "password", "strongpass1",
                                "secondaryPin", "23456789",
                                "rememberDevice", false
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("invite-user"));
    }

    @Test
    void regularUserCannotCreateInvite() throws Exception {
        MockHttpSession hanaSession = loginAndGetSession("hana", "test1234", false);

        mockMvc.perform(post("/api/invites")
                        .with(csrf())
                        .session(hanaSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expiresInHours", 72))))
                .andExpect(status().isForbidden());
    }

    private MockHttpSession loginAndGetSession(String loginId, String password, boolean rememberDevice) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", loginId,
                                "password", password,
                                "secondaryPin", "12345678",
                                "rememberDevice", rememberDevice
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value(loginId))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private String createInviteAndGetToken(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/invites")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expiresInHours", 72))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }
}
