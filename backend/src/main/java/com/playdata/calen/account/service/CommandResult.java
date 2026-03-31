package com.playdata.calen.account.service;

public record CommandResult(
        int exitCode,
        String stdout,
        String stderr
) {
}
