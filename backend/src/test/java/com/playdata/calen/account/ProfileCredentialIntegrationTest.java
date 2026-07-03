package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.security.SecondaryPinSessionSupport;
import jakarta.servlet.http.Cookie;
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
        "spring.datasource.url=jdbc:h2:mem:profile-credential-test-${random.uuid};MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ProfileCredentialIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void verifySecondaryPinEndpointRequiresCorrectPin() throws Exception {
        MockHttpSession session = login("hana", "test1234", "12345678");

        mockMvc.perform(post("/api/auth/profile/verify-secondary-pin")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("secondaryPin", "12345678"))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/profile/verify-secondary-pin")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("secondaryPin", "00000000"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginStoresProtectedSecondaryPinInsteadOfRawValue() throws Exception {
        MockHttpSession session = login("hana", "test1234", "12345678");

        Object storedValue = session.getAttribute(SecondaryPinSessionSupport.VERIFIED_SECONDARY_PIN_KEY);
        assertThat(storedValue).isInstanceOf(String.class);
        assertThat(storedValue).isNotEqualTo("12345678");
    }

    @Test
    void profileCredentialEndpointsRejectMissingCsrf() throws Exception {
        MockHttpSession session = login("hana", "test1234", "12345678");

        mockMvc.perform(post("/api/auth/profile/verify-secondary-pin")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("secondaryPin", "12345678"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/auth/profile/password")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "secondaryPin", "12345678",
                                "newPassword", "newpass1234"
                        ))))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/auth/profile/secondary-pin")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "secondaryPin", "12345678",
                                "newSecondaryPin", "11223344"
                        ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void rememberMeCookieUsesExpectedSecurityAttributes() throws Exception {
        MvcResult loginResult = loginResult("hana", "test1234", "12345678", true);
        Cookie rememberMeCookie = loginResult.getResponse().getCookie("CALEN_REMEMBER_ME");

        assertThat(rememberMeCookie).isNotNull();
        assertThat(rememberMeCookie.getValue()).isNotBlank();
        assertThat(rememberMeCookie.isHttpOnly()).isTrue();
        assertThat(rememberMeCookie.getPath()).isEqualTo("/");
        assertThat(rememberMeCookie.getMaxAge()).isPositive();
        assertThat(rememberMeCookie.getSecure()).isFalse();
    }
    @Test
    void passwordChangeRevokesRememberMeTokens() throws Exception {
        MvcResult loginResult = loginResult("admin", "test1234", "12345678", true);
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        Cookie rememberMeCookie = loginResult.getResponse().getCookie("CALEN_REMEMBER_ME");

        assertThat(session).isNotNull();
        assertThat(rememberMeCookie).isNotNull();
        assertThat(rememberMeCookie.getValue()).isNotBlank();

        MvcResult passwordChangeResult = mockMvc.perform(put("/api/auth/profile/password")
                        .session(session)
                        .cookie(rememberMeCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "secondaryPin", "12345678",
                                "newPassword", "revokedpass1234"
                        ))))
                .andExpect(status().isNoContent())
                .andReturn();

        Cookie clearedCookie = passwordChangeResult.getResponse().getCookie("CALEN_REMEMBER_ME");
        assertThat(clearedCookie).isNotNull();
        assertThat(clearedCookie.getMaxAge()).isZero();

        mockMvc.perform(get("/api/auth/me").cookie(rememberMeCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void secondaryPinChangeRevokesRememberMeTokens() throws Exception {
        MvcResult loginResult = loginResult("admin", "test1234", "12345678", true);
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        Cookie rememberMeCookie = loginResult.getResponse().getCookie("CALEN_REMEMBER_ME");

        assertThat(session).isNotNull();
        assertThat(rememberMeCookie).isNotNull();
        assertThat(rememberMeCookie.getValue()).isNotBlank();

        MvcResult pinChangeResult = mockMvc.perform(put("/api/auth/profile/secondary-pin")
                        .session(session)
                        .cookie(rememberMeCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "secondaryPin", "12345678",
                                "newSecondaryPin", "22334455"
                        ))))
                .andExpect(status().isNoContent())
                .andReturn();

        Cookie clearedCookie = pinChangeResult.getResponse().getCookie("CALEN_REMEMBER_ME");
        assertThat(clearedCookie).isNotNull();
        assertThat(clearedCookie.getMaxAge()).isZero();

        mockMvc.perform(get("/api/auth/me").cookie(rememberMeCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCanChangePasswordAfterSecondaryPinVerification() throws Exception {
        MockHttpSession session = login("hana", "test1234", "12345678");

        mockMvc.perform(put("/api/auth/profile/password")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "secondaryPin", "12345678",
                                "newPassword", "newpass1234"
                        ))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", "hana",
                                "password", "test1234",
                                "secondaryPin", "12345678",
                                "rememberDevice", false
                        ))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", "hana",
                                "password", "newpass1234",
                                "secondaryPin", "12345678",
                                "rememberDevice", false
                        ))))
                .andExpect(status().isOk());
    }

    @Test
    void userCanChangeSecondaryPin() throws Exception {
        MockHttpSession session = login("minsu", "test1234", "87654321");

        mockMvc.perform(put("/api/auth/profile/secondary-pin")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "secondaryPin", "87654321",
                                "newSecondaryPin", "11223344"
                        ))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", "minsu",
                                "password", "test1234",
                                "secondaryPin", "87654321",
                                "rememberDevice", false
                        ))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", "minsu",
                                "password", "test1234",
                                "secondaryPin", "11223344",
                                "rememberDevice", false
                        ))))
                .andExpect(status().isOk());
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
}