package com.playdata.calen.account.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.session.web.http.DefaultCookieSerializer;

class SharedSessionConfigTest {
    @Test
    void authenticatedUserContextCanBeSerializedForRedis() {
        var principal = new AppUserPrincipal(7L, "member", "Member", "test-only-hash",
                com.playdata.calen.account.domain.AppUserRole.USER, true);
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(principal, null, principal.getAuthorities());
        var context = new org.springframework.security.core.context.SecurityContextImpl(authentication);
        var serializer = new org.springframework.data.redis.serializer.JdkSerializationRedisSerializer();

        var restored = (org.springframework.security.core.context.SecurityContext) serializer.deserialize(serializer.serialize(context));

        assertThat(restored.getAuthentication().isAuthenticated()).isTrue();
        assertThat(restored.getAuthentication().getPrincipal()).isEqualTo(principal);
    }

    @Test
    void sharedSessionsAreOptInAndRequireStateRedis() {
        new WebApplicationContextRunner().withUserConfiguration(SharedSessionConfig.class)
                .run(context -> assertThat(context).doesNotHaveBean(SessionRepositoryFilter.class));
        new WebApplicationContextRunner().withUserConfiguration(SharedSessionConfig.class)
                .withPropertyValues("app.session.shared-enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void independentServerFiltersCanReadAndInvalidateSameSession() throws Exception {
        ConcurrentHashMap<String, Session> shared = new ConcurrentHashMap<>();
        var first = filter(new MapSessionRepository(shared));
        var second = filter(new MapSessionRepository(shared));
        var response = new MockHttpServletResponse();
        first.doFilter(new MockHttpServletRequest(), response, (request, ignored) ->
                ((jakarta.servlet.http.HttpServletRequest) request).getSession().setAttribute("user", 7L));
        var cookie = response.getCookie("JSESSIONID");
        assertThat(cookie).isNotNull();
        MockHttpServletRequest followup = new MockHttpServletRequest();
        followup.setCookies(cookie);
        second.doFilter(followup, new MockHttpServletResponse(), (request, ignored) -> {
            var session = ((jakarta.servlet.http.HttpServletRequest) request).getSession(false);
            assertThat(session.getAttribute("user")).isEqualTo(7L);
            session.invalidate();
        });
        assertThat(shared).isEmpty();
    }

    private SessionRepositoryFilter<MapSession> filter(MapSessionRepository repository) {
        var filter = new SessionRepositoryFilter<>(repository);
        var resolver = new org.springframework.session.web.http.CookieHttpSessionIdResolver();
        DefaultCookieSerializer cookies = new SharedSessionConfig().cookieSerializer(false);
        resolver.setCookieSerializer(cookies);
        filter.setHttpSessionIdResolver(resolver);
        return filter;
    }
}
