package com.playdata.calen.ledger.web;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AdminPageAccessService;
import com.playdata.calen.account.service.LoginAuditLogService;
import com.playdata.calen.ledger.dto.LedgerImageConcurrencyResponse;
import com.playdata.calen.ledger.ocr.LedgerImageConcurrencyService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = LedgerImageConcurrencyControllerTest.Config.class)
class LedgerImageConcurrencyControllerTest {
    @Configuration @EnableWebMvc @EnableWebSecurity @EnableMethodSecurity
    static class Config {
        @Bean LedgerImageConcurrencyService settings() { return mock(LedgerImageConcurrencyService.class); }
        @Bean AdminPageAccessService access() { return mock(AdminPageAccessService.class); }
        @Bean LoginAuditLogService audit() { return mock(LoginAuditLogService.class); }
        @Bean LedgerImageConcurrencyController controller(LedgerImageConcurrencyService settings, AdminPageAccessService access, LoginAuditLogService audit) {
            return new LedgerImageConcurrencyController(settings, access, audit);
        }
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).httpBasic(Customizer.withDefaults()).build();
        }
    }
    @Autowired WebApplicationContext context;
    @Autowired LedgerImageConcurrencyService settings;
    @Autowired AdminPageAccessService access;
    @Autowired LoginAuditLogService audit;
    MockMvc mvc;
    final AppUserPrincipal ordinary = new AppUserPrincipal(7L, "user", "User", "", AppUserRole.USER, true);
    final AppUserPrincipal admin = new AppUserPrincipal(1L, "admin", "Admin", "", AppUserRole.ADMIN, true);
    final String path = "/api/admin/ops-control/image-analysis";

    @BeforeEach void setup() {
        reset(settings, access, audit);
        when(settings.getSettings()).thenReturn(new LedgerImageConcurrencyResponse(3, 1, 20));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void authenticatedUsersCanReadOnlyTheNonSecretPolicy() throws Exception {
        mvc.perform(get("/api/ledger/image-analysis/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ledger/image-analysis/settings").with(user(ordinary)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maxConcurrent").value(3));
    }
    @Test void normalUsersCannotReadOrWriteAdminSettings() throws Exception {
        mvc.perform(get(path).with(user(ordinary))).andExpect(status().isForbidden());
        mvc.perform(patch(path).with(user(ordinary)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"maxConcurrent\":7}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(settings);
    }
    @Test void adminSecondaryVerificationAndCsrfRemainRequired() throws Exception {
        doThrow(new AccessDeniedException("verification required")).when(access).requireVerified(any(), eq(1L));
        mvc.perform(get(path).with(user(admin))).andExpect(status().isForbidden());
        mvc.perform(patch(path).with(user(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"maxConcurrent\":4}"))
                .andExpect(status().isForbidden());
        reset(access);
        mvc.perform(patch(path).with(user(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"maxConcurrent\":4}"))
                .andExpect(status().isForbidden());
        verify(settings, never()).update(any());
    }
    @Test void validatedAdminChangesAreSavedAndAudited() throws Exception {
        when(settings.update(5)).thenReturn(new LedgerImageConcurrencyResponse(5, 1, 20));
        mvc.perform(patch(path).with(user(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"maxConcurrent\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maxConcurrent").value(5));
        verify(access).requireVerified(any(), eq(1L));
        verify(audit).recordAdminAction(eq(1L), eq("admin"), anyString(), isNull(), eq("IMAGE_ANALYSIS_CONCURRENCY_UPDATE:maxConcurrent=5"));
    }
    @Test void nullZeroOutOfRangeAndFractionalLimitsAreRejected() throws Exception {
        for (String value : new String[]{"null", "0", "-1", "21", "3.5", "1.1"}) {
            mvc.perform(patch(path).with(user(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"maxConcurrent\":" + value + "}"))
                    .andExpect(status().isBadRequest());
        }
        verify(settings, never()).update(any());
    }
}
