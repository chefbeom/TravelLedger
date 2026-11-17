package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.AdminService;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.servlet.http.Cookie;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(properties = {
        "app.seed.enabled=true", "app.seed.allow-insecure-default-credentials=true",
        "spring.datasource.url=jdbc:h2:mem:admin-promotion-${random.uuid};MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
class AdminPromotionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserService appUsers;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminService admins;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test void cachedAdministratorPinAndTargetStateAreRecheckedBeforePromotion() throws Exception {
        var actor = appUsers.registerUser("a" + UUID.randomUUID().toString().replace("-", ""),
                "관리자", "test-password", "12345678", AppUserRole.ADMIN);
        var target = appUsers.registerUser("t" + UUID.randomUUID().toString().replace("-", ""),
                "대상", "test-password", "12345678");
        var requestEntityManager = entityManagerFactory.createEntityManager();
        var pool = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntityManager));
        try {
            AppUser stale = requestEntityManager.find(AppUser.class, actor.getId());
            String oldHash = stale.getSecondaryPinHash();
            pool.submit(() -> appUsers.updateSecondaryPin(actor.getId(), "12345678", "87654321"))
                    .get(5, TimeUnit.SECONDS);
            assertThat(stale.getSecondaryPinHash()).isEqualTo(oldHash);
            assertThatThrownBy(() -> admins.promoteUser(actor.getId(), target.getId(), "12345678", "127.0.0.1", "test"))
                    .isInstanceOf(BadCredentialsException.class);
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestEntityManager.close();
            pool.shutdownNow();
        }
        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(AppUserRole.USER);

        var secondContext = entityManagerFactory.createEntityManager();
        var secondPool = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(secondContext));
        try {
            AppUser staleTarget = secondContext.find(AppUser.class, target.getId());
            secondPool.submit(() -> admins.updateUserActive(-1L, target.getId(), false)).get(5, TimeUnit.SECONDS);
            assertThat(staleTarget.isActive()).isTrue();
            assertThatThrownBy(() -> admins.promoteUser(actor.getId(), target.getId(), "87654321", "127.0.0.1", "test"))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("활성 사용자");
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            secondContext.close();
            secondPool.shutdownNow();
        }
        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(AppUserRole.USER);
    }

    @Test void promotionRequiresVerifiedAdminCsrfAndFreshPinAndIsAuditedAtomically() throws Exception {
        String loginId = "promote" + UUID.randomUUID().toString().replace("-", "");
        var target = appUsers.registerUser(loginId, "승격 대상", "test-password", "23456789");
        var admin = users.findByLoginId("admin").orElseThrow();
        MvcResult targetLogin = login(loginId, "test-password", "23456789", true);
        MockHttpSession oldUserSession = (MockHttpSession) targetLogin.getRequest().getSession(false);
        Cookie rememberCookie = targetLogin.getResponse().getCookie("CALEN_REMEMBER_ME");
        assertThat(rememberCookie).isNotNull();
        MockHttpSession adminSession = (MockHttpSession) login("admin", "test1234", "12345678", false)
                .getRequest().getSession(false);
        String path = "/api/admin/users/" + target.getId() + "/administrator";
        String correct = json.writeValueAsString(Map.of("secondaryPin", "12345678"));

        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(correct))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).session(oldUserSession).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(correct))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).session(adminSession).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(correct))
                .andExpect(status().isForbidden());
        verifyAdminAccess(adminSession, "12345678");
        mvc.perform(post(path).session(adminSession).contentType(MediaType.APPLICATION_JSON).content(correct))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).session(adminSession).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("secondaryPin", "87654321"))))
                .andExpect(status().isUnauthorized());
        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(AppUserRole.USER);
        assertThat(jdbc.queryForObject("select count(*) from login_audit_logs where detail = ?", Integer.class,
                "ADMIN_ROLE_GRANT:userId=" + target.getId())).isZero();

        mvc.perform(post(path).session(adminSession).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(correct))
                .andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.id").value(target.getId()));
        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(AppUserRole.ADMIN);
        assertThat(jdbc.queryForObject("select count(*) from login_audit_logs where detail = ? and app_user_id = ?", Integer.class,
                "ADMIN_ROLE_GRANT:userId=" + target.getId(), admin.getId())).isEqualTo(1);
        mvc.perform(get("/api/auth/me").cookie(rememberCookie)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/dashboard").session(oldUserSession)).andExpect(status().isForbidden());

        MockHttpSession newAdminSession = (MockHttpSession) login(loginId, "test-password", "23456789", false)
                .getRequest().getSession(false);
        verifyAdminAccess(newAdminSession, "23456789");
        mvc.perform(get("/api/admin/dashboard").session(newAdminSession)).andExpect(status().isOk());
        mvc.perform(post(path).session(adminSession).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(correct))
                .andExpect(status().isBadRequest());
    }

    @Test void inactiveUserCannotBePromoted() throws Exception {
        var target = appUsers.registerUser("inactive" + UUID.randomUUID().toString().replace("-", ""),
                "비활성", "test-password", "23456789");
        target.setActive(false); users.saveAndFlush(target);
        MockHttpSession session = (MockHttpSession) login("admin", "test1234", "12345678", false)
                .getRequest().getSession(false);
        verifyAdminAccess(session, "12345678");
        mvc.perform(post("/api/admin/users/{id}/administrator", target.getId()).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"secondaryPin\":\"12345678\"}"))
                .andExpect(status().isBadRequest());
        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(AppUserRole.USER);
    }

    private MvcResult login(String loginId, String password, String pin, boolean remember) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginId", loginId, "password", password,
                                "secondaryPin", pin, "rememberDevice", remember))))
                .andExpect(status().isOk()).andReturn();
    }

    private void verifyAdminAccess(MockHttpSession session, String pin) throws Exception {
        mvc.perform(post("/api/admin/access/verify").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", pin))))
                .andExpect(status().isNoContent());
    }
}
