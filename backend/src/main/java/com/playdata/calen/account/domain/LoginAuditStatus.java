package com.playdata.calen.account.domain;

public enum LoginAuditStatus {
    SUCCESS(true),
    BLOCKED(false),
    BAD_CREDENTIALS(false),
    BAD_SECONDARY_PIN(false);

    private final boolean success;

    LoginAuditStatus(boolean success) {
        this.success = success;
    }

    public boolean isSuccess() {
        return success;
    }
}
