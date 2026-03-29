package com.playdata.calen.account.domain;

public enum AppUserRole {
    USER,
    ADMIN;

    public boolean isAdmin() {
        return this == ADMIN;
    }
}
