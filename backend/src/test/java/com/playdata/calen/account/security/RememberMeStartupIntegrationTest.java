package com.playdata.calen.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.service.AppUserService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:remember-startup-${random.uuid};MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
class RememberMeStartupIntegrationTest {
    @Autowired AppUserService users;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test void publicStartupRequestDoesNotRotateCookieBeforeSessionRestoration() throws Exception {
        String loginId = "startup" + UUID.randomUUID().toString().replace("-", "");
        users.registerUser(loginId, "테스트", "test-password", "12345678");
        var login = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("loginId", loginId, "password", "test-password",
                        "secondaryPin", "12345678", "rememberDevice", true))))
                .andExpect(status().isOk()).andReturn();
        var cookie = login.getResponse().getCookie("CALEN_REMEMBER_ME");
        assertThat(cookie).isNotNull();
        var publicResponse = mvc.perform(get("/api/auth/registration-options").cookie(cookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(publicResponse.getResponse().getCookie("CALEN_REMEMBER_ME")).isNull();
        var restored = mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk()).andReturn();
        var rotated = restored.getResponse().getCookie("CALEN_REMEMBER_ME");
        assertThat(rotated).isNotNull();
        mvc.perform(get("/api/auth/me").cookie(rotated)).andExpect(status().isOk());
    }
}
