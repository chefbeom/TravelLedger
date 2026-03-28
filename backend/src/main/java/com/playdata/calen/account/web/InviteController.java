package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.AccountInviteAcceptRequest;
import com.playdata.calen.account.dto.AccountInviteCreateRequest;
import com.playdata.calen.account.dto.AccountInviteCreateResponse;
import com.playdata.calen.account.dto.AccountInviteDetailsResponse;
import com.playdata.calen.account.dto.AppUserResponse;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AccountInviteService;
import com.playdata.calen.account.service.AppUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invites")
public class InviteController {

    private final AccountInviteService accountInviteService;
    private final AppUserService appUserService;

    public InviteController(AccountInviteService accountInviteService, AppUserService appUserService) {
        this.accountInviteService = accountInviteService;
        this.appUserService = appUserService;
    }

    @PostMapping
    public AccountInviteCreateResponse createInvite(
            Authentication authentication,
            @Valid @RequestBody(required = false) AccountInviteCreateRequest request
    ) {
        AppUserPrincipal principal = requirePrincipal(authentication);
        return accountInviteService.createInvite(
                principal.userId(),
                request != null ? request.expiresInHours() : null
        );
    }

    @GetMapping("/{token}")
    public AccountInviteDetailsResponse getInviteDetails(@PathVariable String token) {
        return accountInviteService.getInviteDetails(token);
    }

    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public AppUserResponse acceptInvite(@Valid @RequestBody AccountInviteAcceptRequest request) {
        return appUserService.toResponse(
                accountInviteService.acceptInvite(
                        request.token(),
                        request.loginId(),
                        request.displayName(),
                        request.password()
                )
        );
    }

    private AppUserPrincipal requirePrincipal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new AccessDeniedException("초대 링크를 만들 권한이 없습니다.");
        }
        return principal;
    }
}
