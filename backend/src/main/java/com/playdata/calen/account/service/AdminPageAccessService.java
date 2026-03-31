package com.playdata.calen.account.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class AdminPageAccessService {

    private static final String VERIFIED_ADMIN_USER_ID_KEY = "VERIFIED_ADMIN_USER_ID";
    private static final String VERIFIED_ADMIN_DATE_KEY = "VERIFIED_ADMIN_DATE";
    private static final int ADMIN_CODE_BASE = 19990515;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public boolean isVerified(HttpServletRequest request, Long userId) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }

        Object verifiedUserId = session.getAttribute(VERIFIED_ADMIN_USER_ID_KEY);
        Object verifiedDate = session.getAttribute(VERIFIED_ADMIN_DATE_KEY);
        String today = currentDateString();

        return userId != null
                && verifiedUserId instanceof Long storedUserId
                && userId.equals(storedUserId)
                && today.equals(verifiedDate);
    }

    public void verify(HttpServletRequest request, Long userId, String code) {
        if (!expectedCode().equals(code != null ? code.trim() : "")) {
            throw new AccessDeniedException("관리자 추가 인증 코드가 올바르지 않습니다.");
        }

        HttpSession session = request.getSession(true);
        session.setAttribute(VERIFIED_ADMIN_USER_ID_KEY, userId);
        session.setAttribute(VERIFIED_ADMIN_DATE_KEY, currentDateString());
    }

    public void requireVerified(HttpServletRequest request, Long userId) {
        if (!isVerified(request, userId)) {
            throw new AccessDeniedException("관리자 추가 인증이 필요합니다.");
        }
    }

    private String expectedCode() {
        int today = Integer.parseInt(currentDateString());
        return String.valueOf(ADMIN_CODE_BASE + today);
    }

    private String currentDateString() {
        return LocalDate.now(KST).format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
