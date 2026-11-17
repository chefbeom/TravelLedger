package com.playdata.calen.account.dto;

import com.playdata.calen.account.domain.AppUserRole;

public record AppUserResponse(
        Long id,
        String loginId,
        String displayName,
        AppUserRole role,
        boolean admin,
        boolean active,
        boolean localLoginEnabled,
        String email,
        boolean emailVerified
) {
}
