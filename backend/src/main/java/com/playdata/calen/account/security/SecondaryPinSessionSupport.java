package com.playdata.calen.account.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

public final class SecondaryPinSessionSupport {

    public static final String VERIFIED_SECONDARY_PIN_KEY = "VERIFIED_SECONDARY_PIN";

    private SecondaryPinSessionSupport() {
    }

    public static void storeVerifiedSecondaryPin(HttpServletRequest request, String secondaryPin) {
        HttpSession session = request.getSession(true);
        session.setAttribute(VERIFIED_SECONDARY_PIN_KEY, secondaryPin);
    }

    public static String getVerifiedSecondaryPin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(VERIFIED_SECONDARY_PIN_KEY);
        return value instanceof String text ? text : null;
    }
}
