package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.AdminAccessStatusResponse;
import com.playdata.calen.account.dto.AdminAccessVerifyRequest;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AdminPageAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/access")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAccessController {

    private final AdminPageAccessService adminPageAccessService;

    @GetMapping("/status")
    public AdminAccessStatusResponse getStatus(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            HttpServletRequest httpRequest
    ) {
        return new AdminAccessStatusResponse(adminPageAccessService.isVerified(httpRequest, currentUser.userId()));
    }

    @PostMapping("/verify")
    public ResponseEntity<Void> verify(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody AdminAccessVerifyRequest request,
            HttpServletRequest httpRequest
    ) {
        adminPageAccessService.verify(httpRequest, currentUser.userId(), request.code());
        return ResponseEntity.noContent().build();
    }
}
