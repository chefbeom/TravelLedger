package com.playdata.calen.account.web;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.dto.AppUserResponse;
import com.playdata.calen.account.dto.AuthLoginRequest;
import com.playdata.calen.account.dto.AuthRegisterRequest;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.LoginAttemptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AppUserService appUserService;
    private final LoginAttemptService loginAttemptService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final PersistentTokenBasedRememberMeServices rememberMeServices;

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrfToken) {
        return Map.of(
                "headerName", csrfToken.getHeaderName(),
                "parameterName", csrfToken.getParameterName(),
                "token", csrfToken.getToken()
        );
    }

    @PostMapping("/register")
    public AppUserResponse register(
            @Valid @RequestBody AuthRegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        AppUser user = appUserService.registerUser(request.loginId(), request.displayName(), request.password());
        Authentication authentication = authenticate(request.loginId(), request.password());
        signIn(authentication, request.rememberDevice(), httpRequest, httpResponse);
        return appUserService.toResponse(user);
    }

    @PostMapping("/login")
    public AppUserResponse login(
            @Valid @RequestBody AuthLoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        String normalizedLoginId = request.loginId().trim();
        String clientIp = resolveClientIp(httpRequest);
        loginAttemptService.ensureAllowed(normalizedLoginId, clientIp);

        Authentication authentication;
        try {
            authentication = authenticate(normalizedLoginId, request.password());
        } catch (AuthenticationException exception) {
            loginAttemptService.recordFailure(normalizedLoginId, clientIp);
            throw exception;
        }

        loginAttemptService.recordSuccess(normalizedLoginId, clientIp);
        signIn(authentication, request.rememberDevice(), httpRequest, httpResponse);
        return appUserService.toResponse(appUserService.getRequiredUser(((AppUserPrincipal) authentication.getPrincipal()).userId()));
    }

    @GetMapping("/me")
    public ResponseEntity<AppUserResponse> me(
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        if (!(authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            clearAuthentication(authentication, httpRequest, httpResponse);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(appUserService.toResponse(appUserService.getRequiredUser(principal.userId())));
        } catch (Exception exception) {
            clearAuthentication(authentication, httpRequest, httpResponse);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        clearAuthentication(authentication, httpRequest, httpResponse);
        return ResponseEntity.noContent().build();
    }

    private Authentication authenticate(String loginId, String password) {
        return authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(loginId.trim(), password)
        );
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void signIn(
            Authentication authentication,
            boolean rememberDevice,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        if (rememberDevice) {
            rememberMeServices.loginSuccess(httpRequest, httpResponse, authentication);
        } else {
            rememberMeServices.logout(httpRequest, httpResponse, authentication);
        }
    }

    private void clearAuthentication(
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        rememberMeServices.logout(httpRequest, httpResponse, authentication);
        SecurityContextHolder.clearContext();
        if (httpRequest.getSession(false) != null) {
            httpRequest.getSession(false).invalidate();
        }
    }
}
