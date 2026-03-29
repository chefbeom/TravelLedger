package com.playdata.calen.account.security;

import org.springframework.security.authentication.BadCredentialsException;

public class SecondaryPinMismatchException extends BadCredentialsException {

    public SecondaryPinMismatchException() {
        super("2차 비밀번호가 올바르지 않습니다.");
    }
}
