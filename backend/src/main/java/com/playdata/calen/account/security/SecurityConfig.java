package com.playdata.calen.account.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.CookieTheftException;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            PersistentTokenBasedRememberMeServices rememberMeServices,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled
    ) throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName("_csrf");
        AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers(h2ConsoleEnabled ? "/h2-console/**" : "/__disabled-h2-console__")
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .rememberMe(remember -> remember.rememberMeServices(rememberMeServices))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                            "/api/auth/csrf",
                            "/api/auth/login",
                            "/api/auth/me",
                            "/api/auth/logout",
                            "/api/invites/*",
                            "/api/invites/accept",
                            "/api/file/public-download/*",
                            "/api/travel/public/**",
                            "/actuator/health",
                            "/actuator/health/**",
                            "/actuator/prometheus",
                            "/error"
                    ).permitAll();
                    if (h2ConsoleEnabled) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                            if (authentication == null || trustResolver.isAnonymous(authentication)) {
                                writeUnauthorized(request, response, null);
                                return;
                            }
                            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Access is denied.");
                        })
                )
                .cors(Customizer.withDefaults())
                .addFilterAfter(csrfCookieFilter(), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository(
            @Value("${app.security.csrf-secure-cookie:true}") boolean secureCookie
    ) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.secure(secureCookie).sameSite("Lax"));
        return repository;
    }

    @Bean
    public OncePerRequestFilter csrfCookieFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain filterChain
            ) throws ServletException, IOException {
                CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                if (csrfToken != null) {
                    csrfToken.getToken();
                }
                filterChain.doFilter(request, response);
            }
        };
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public PersistentTokenRepository persistentTokenRepository(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                create table if not exists persistent_logins (
                    username varchar(64) not null,
                    series varchar(64) primary key,
                    token varchar(64) not null,
                    last_used timestamp not null
                )
                """);

        JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
        repository.setDataSource(dataSource);
        return repository;
    }

    @Bean
    public PersistentTokenBasedRememberMeServices rememberMeServices(
            UserDetailsService userDetailsService,
            PersistentTokenRepository persistentTokenRepository,
            @Value("${app.security.remember-me-key:}") String rememberMeKey,
            @Value("${app.security.remember-me-token-validity-seconds:2592000}") int tokenValiditySeconds,
            @Value("${app.security.remember-me-secure-cookie:true}") boolean rememberMeSecureCookie
    ) {
        String resolvedRememberMeKey = SecuritySecretSupport.resolve(rememberMeKey, "remember-me");
        PersistentTokenBasedRememberMeServices services = new PersistentTokenBasedRememberMeServices(
                resolvedRememberMeKey,
                userDetailsService,
                persistentTokenRepository
        ) {
            @Override
            protected UserDetails processAutoLoginCookie(
                    String[] cookieTokens,
                    HttpServletRequest request,
                    HttpServletResponse response
            ) {
                try {
                    return super.processAutoLoginCookie(cookieTokens, request, response);
                } catch (CookieTheftException exception) {
                    throw new InvalidCookieException("Invalid remember-me cookie.");
                }
            }
        };
        services.setCookieName("CALEN_REMEMBER_ME");
        services.setAlwaysRemember(true);
        services.setUseSecureCookie(rememberMeSecureCookie);
        services.setTokenValiditySeconds(tokenValiditySeconds);
        return services;
    }

    private void writeUnauthorized(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException {
        writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "\uB85C\uADF8\uC778\uC774 \uD544\uC694\uD569\uB2C8\uB2E4.");
    }

    private void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), java.util.Map.of(
                "status", status,
                "message", message
        ));
    }
}
