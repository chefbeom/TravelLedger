package com.playdata.calen.account;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        "spring.datasource.url=jdbc:h2:mem:privacy-controller-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class PrivacyControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void publicDownloadLinkRevocationRequiresAuthenticationAndCsrf() throws Exception {
        MockHttpSession session = login("hana", "test1234", "12345678");

        mockMvc.perform(delete("/api/privacy/public-download-links").with(csrf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/privacy/public-download-links").session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/privacy/public-download-links")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiAnalysisHistoriesDeleted").value(0))
                .andExpect(jsonPath("$.publicDownloadLinksRevoked").isNumber())
                .andExpect(jsonPath("$.processedAt").isNotEmpty());
    }

    @Test
    void sensitiveCleanupRequiresAuthenticationAndCsrf() throws Exception {
        MockHttpSession session = login("hana", "test1234", "12345678");

        mockMvc.perform(post("/api/privacy/cleanup").with(csrf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/privacy/cleanup").session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/privacy/cleanup")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiAnalysisHistoriesDeleted").isNumber())
                .andExpect(jsonPath("$.publicDownloadLinksRevoked").isNumber())
                .andExpect(jsonPath("$.processedAt").isNotEmpty());
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
}