package com.playdata.calen.account.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminPageAccessService {

    private static final String VERIFIED_ADMIN_USER_ID_KEY = "VERIFIED_ADMIN_USER_ID";
    private static final String VERIFIED_ADMIN_AT_KEY = "VERIFIED_ADMIN_AT";
    private static final Duration VERIFICATION_TTL = Duration.ofMinutes(30);

    private final AppUserService appUserService;

    public boolean isVerified(HttpServletRequest request, Long userId) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }

        Object verifiedUserId = session.getAttribute(VERIFIED_ADMIN_USER_ID_KEY);
        Object verifiedAt = session.getAttribute(VERIFIED_ADMIN_AT_KEY);

        return userId != null
                && verifiedUserId instanceof Long storedUserId
                && userId.equals(storedUserId)
                && verifiedAt instanceof Instant verifiedInstant
                && verifiedInstant.plus(VERIFICATION_TTL).isAfter(Instant.now());
    }

    public void verify(HttpServletRequest request, Long userId, String secondaryPin) {
        appUserService.verifySecondaryPin(userId, secondaryPin);

        HttpSession session = request.getSession(true);
        session.setAttribute(VERIFIED_ADMIN_USER_ID_KEY, userId);
        session.setAttribute(VERIFIED_ADMIN_AT_KEY, Instant.now());
    }

    public void requireVerified(HttpServletRequest request, Long userId) {
        if (!isVerified(request, userId)) {
            throw new AccessDeniedException("Administrator verification is required.");
        }
    }
}