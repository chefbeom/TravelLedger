package com.playdata.calen.account.dto;

public record AppUserResponse(
        Long id,
        String loginId,
        String displayName,
        boolean active
) {
}
